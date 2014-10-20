(ns defragment.core
  (:gen-class)
  (:import [adamb.vorbis VorbisCommentHeader VorbisIO CommentField CommentUpdater])
  (:require [me.raynes.conch :refer [programs with-programs let-programs] :as csh]
            [me.raynes.conch.low-level :as sh]
            [utilza.repl :as urepl]
            [clojure.tools.trace :as trace]
            [clojure.string :as s]
            [useful.map :as um]
            [utilza.java :as ujava]
            [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [utilza.misc :as umisc]
            [taoensso.timbre :as log]
            [clojure.string :as st]))



;; IMPORTANT: This bare exec is here to dothis FIRST before running anything, at compile time
(log/merge-config! {:appenders {:spit {:enabled? true
                                       :fmt-output-opts {:nofonts? true}}
                                :standard-out {:enabled? true
                                               ;; nrepl/cider/emacs hates the bash escapes.
                                               :fmt-output-opts {:nofonts? true}}}
                    ;; TODO: should only be in dev profile/mode
                    :shared-appender-config {:spit-filename "defragment.log"}})


;; IMPORTANT: enables the very very awesome use of clojure.tools.trace/trace-vars , etc
;; and logs the output of those traces to whatever is configured for timbre at the moment!
(alter-var-root #'clojure.tools.trace/tracer (fn [_]
                                               (fn [name value]
                                                 (log/debug name value))))



(programs bash mv)


(defn get-oggs
  "Takes directory path.
   Reuurns a seq of strings of the ogg files in that dir"
  [dirpath]
  (->> dirpath
       java.io.File.
       .listFiles
       (map #(.toString  %) )
       (filter #(.endsWith % ".ogg"))))

(defn unogg
  "Strip the .suffix from a string.
   Can not handle . in the name!"
  [s]
  (-> s
      (st/split  #"\.")
      first))


(defn path-sep
  "Basically, basename: Separate a filepath,
   return a vector of [path, basename]"
  [s]
  (let [all (.split s "/")]
    [(->> all butlast (interpose "/") (apply str))
     (->  all last)]))

(defn formatted-date
  "Takes a filepath map.
  Returns the year-month-day string."
  [{:keys [year month day]}]
  (apply str (interpose "-" [year month day])))

(defn formatted-datetime
  "Takes a filepath map, returns the date and time as
   a single long, with digits: yymmddhhmmss"
  [{:keys [year month day time]}]
  (Long/parseLong (str year month day (.replace time "_" ""))))


(defn tokenize
  "Takes a string with a filepath, with tokens: year-month-day-hr_min_sec-show.ogg,
  separated by - chars. The 'show' token is optional.
   Returns a map with tokenized structure of that filename."
  [s]
  (let [[year month day time & show] (.split s "-")
        keyed (um/keyed [year month day time])]
    (cond-> {:date (formatted-date keyed)
             :datetime (formatted-datetime keyed)}
            show (assoc :show (first show)))))


(defn parse
  "Takes a string wihth a fiiepath.
   Removes the ogg suffix because that gets in our way later.
   Returns a map with the tokenized processed structure of each filename
   and the filename itself as :full-path"
  [filepath]
  (let [[path fname] (path-sep filepath)]
    (-> fname
        unogg
        tokenize
        (assoc :full-path filepath))))


(def date-key-fn (juxt :year :month :day :time))

(defn sort-and-partition
  "Takes a key and a seq of filename maps.
   Return a seq of [[k [filemap...]]], sorted and grouped by k"
  [k files]
  (->> files
       (filter k)
       (sort-by k)
       (group-by k)
       (partition-by k)
       ;; necessary?
       first))






(defn date-time-sort-partition
  "Takes a seq of filemaps.
   Sorts and groups them by DATE, not datetime,
   and returns a seq of [[date [filemap...]]]"
  [files]
  (->> files
       (sort-by :datetime)
       (group-by :date)
       (partition-by :date)
       ;; WAIT, that watch aint' waterproof!
       first))

(defn glom-show
  "Takes a seq of filemaps, and a name of the show.
  Returns a map of :name :date, and :filenames which is a seq of filemaps,
  properly grouped and sorted by date."
  ([show-files show-name]
     (let [sorted (sort-by :datetime show-files)]
       {:name show-name
        :date (-> sorted first :date)
        :filenames (map :full-path sorted)}))
  ([files]
     (glom-show files "")))


(defn prepare-shows
  "Takes a seq of filemaps.
   Returns a seq of filemaps grouped by show and date.
   Use this one for shows with titles and metadata"
  [files]
  (for [[show-name show-files] (sort-and-partition :show files)]
    (glom-show show-files show-name)))


(defn format-show-save
  [path name date]
  (format "%s/%s-%s.ogg"  path date name))


(defn gen-command-line
  "Takes a file glob, returns the string command to process it"
  [cmd-path path {:keys [name date filenames]}]
  (let [cmd (format "cat %s | %s > %s"
                    (umisc/inter-str " " filenames)
                    cmd-path
                    (format-show-save path name date))]
    (format "echo \"%s\"\n%s\n" cmd cmd)))


(defn gen-filenames
  [cmd-path path files]
  (umisc/inter-str "\n" (map (partial gen-command-line cmd-path path)  files)))





(defn without-shows
  [files]
  (for [[show-name show-files] (->> files
                                    (remove :show)
                                    date-time-sort-partition)]
    (glom-show show-files)))


;; TODO: protocol maybe?
(defn header->vec
  "Takes array of CommentFields and translates to a proper Clojure vector"
  [hs]
  (for [^CommentField h hs]
    [(-> h .name s/lower-case keyword) (.value h)]))


(defn album-mover
  []
  (reify CommentUpdater
    (updateComments [this comments]
      (boolean
       (let [fields (.fields comments)
             albums (doall (filter #(-> % .name (= "ALBUM")) fields))
             artists (doall (filter #(-> % .name (= "ARTIST")) fields))]
         (log/debug "moving albums" fields)
         (when-let [new-artist (some-> albums first .value)]
           (log/debug "found album, moving" new-artist)
           (doseq [r (concat artists albums)]
             (log/debug "album-fixer removing" r)
             (.remove fields r))
           (.add fields (CommentField. "ARTIST" new-artist))
           ;; return true if updated, false if not
           true))))))



(defn bad-title
  [f]
  (let [n (.name f)
        v (.value f)]
    (and (= "TITLE" n)
         (or (= "Unknown" (s/trim v))
             (= "%Y-%m-%d" (s/trim v))
             (empty? v)))))

;; XXX this function ought to be taken out and shot
(defn title-fixer
  [^String date]
  (reify CommentUpdater
    (updateComments [this comments]
      (boolean
       (let [fields (.fields comments)
             unknowns (doall (filter bad-title fields))]
         (log/debug "fixing titles" fields)
         (when (not (empty? unknowns))
           (log/debug "got unknowns" unknowns)
           (doseq [u unknowns]
             (log/debug "title-fixer removing" u)
             (.remove fields u))
           (.add fields (CommentField. "TITLE" date))
           ;; return true if updated, false if not
           true))))))



(defn fix-in-place
  [fpath fixer]
  (log/debug "fixing files" fpath)
  (try
    (-> fpath
        jio/as-file
        (VorbisIO/writeComments fixer))
    (catch Exception e
      (log/error e))))


(defn fix-comments
  [path {:keys [name date]}]
  (let [out-path (format-show-save path name date)]
    (log/debug "fixing comments" out-path)
    (doseq [f [(title-fixer date) (album-mover)]]
      (fix-in-place out-path f))))


(defn move-originals!
  [{:keys [filenames]} backup-dir]
  (let [backup-dir (if (.endsWith backup-dir "/") backup-dir (str backup-dir "/"))]
    (doseq [f filenames]
      (log/debug "moving" f "to" backup-dir)
      (mv f backup-dir))))
  

(defn execute!
  [cmd-path path out-commands-file backup-dir fileglob]
  (spit out-commands-file (gen-command-line cmd-path path fileglob))
  (let [{:keys [stdout stderr exit-code]} (bash out-commands-file {:verbose true})]
    (log/debug {:doing stdout, :result stderr, :status @exit-code})
    (when (not= 0 @exit-code)
      ;; TODO: throw exception too?
      (log/error {:doing stdout, :result stderr, :status @exit-code}))
    (fix-comments path fileglob)
    (move-originals! fileglob backup-dir)
    ))



(defn execute-all!
  [cmd-path path out-commands-file backup-dir files]
  (doseq [f files]
    (log/info f)
    (execute! cmd-path path out-commands-file backup-dir f)))


(defn prepare-all
  [files]
  (concat (prepare-shows files) (without-shows files)))


(defn run-all
  [{:keys [in-oggs-path cmd-path out-oggs-path out-commands-file backup-dir]}]
  [{:pre [(assert (every? (comp not empty?) [in-oggs-path cmd-path backup-dir
                                             out-commands-file out-oggs-path]))]}]
  ;; TODO: check valid things, like all the directories actually exist!
  (->> in-oggs-path
       get-oggs
       (map parse)
       prepare-all
       (execute-all! cmd-path out-oggs-path out-commands-file backup-dir)))




(defn process-config
  [config-path]
  (->> config-path
       slurp
       edn/read-string))



(defn revision-info
  []
  (let [{:keys [version revision]} (ujava/get-project-properties "defragment" "defragment")]
    (format "Version: %s, Revision %s" version revision)))


(defn -main [config-path & args]
  (log/info "Welcome to Defragment " (revision-info))
  (log/info "Loading config file " config-path)
  (-> config-path
      process-config
      run-all))



(comment

  (-main "resources/test-config.edn")


  )

