(ns defragment.core
  (:gen-class)
  (:import [adamb.vorbis VorbisCommentHeader VorbisIO CommentField CommentUpdater])
  (:require [me.raynes.conch :refer [programs with-programs let-programs] :as csh]
            [me.raynes.conch.low-level :as sh]
            [taoensso.timbre.appenders.core :as appenders]
            [utilza.repl :as urepl]
            [clojure.tools.trace :as trace]
            [useful.map :as um]
            [defragment.feed :as feed]
            [utilza.java :as ujava]
            [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [utilza.file :as file]
            [utilza.misc :as umisc]
            [taoensso.timbre :as log]
            [clojure.string :as st]))





;; IMPORTANT: This bare exec is here to dothis FIRST before running anything, at compile time
(log/merge-config! {:output-fn (partial log/default-output-fn {:stacktrace-fonts {}})
                    :appenders {:println (appenders/println-appender {:enabled? false})
                                :spit (appenders/spit-appender
                                       {:fname "defragment.log4j"})}})


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
  (file/file-names dirpath #".*?\.ogg"))

(defn unogg
  "Strip the .suffix from a string.
   WARNING: Can not handle . in the name!"
  [s]
  (-> s
      (st/split  #"\.")
      first))



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
  [filepath filename]
  (-> filename
      unogg
      tokenize
      (assoc :full-path (str filepath "/" filename))))



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
  "Takes file path, show name, and date.
   Returns a string with the outgoing show filename and path, with ogg extension"
  [path name date]
  (format "%s/%s-%s.ogg"  path date name))


(defn gen-command-line
  "Takes a concatenating file command full path (cmd-path),
  the path to where the output file is to be saved,
  and a file glob (name, date, filenames)
  Returns the string shell command to concatenate all the filenames,
  to the output dir, named properly with name and date."
  [cmd-path path {:keys [name date filenames]}]
  (let [cmd (format "nice -n 15 ogg123 --audio-buffer 0 -q -d raw -o byteorder:little -f - %s | nice -n 15 oggenc -Q -r - > %s"
                    (umisc/inter-str " " filenames)
                    (format-show-save path name date))]
    (format "echo \"%s\"\n%s\n" cmd cmd)))




(defn without-shows
  "Takes a list of raw ogg files.
   Filters only those without valid shows (in filename).
   Returns a seq of file globs (name, date, filenames) for them"
  [files]
  (for [[show-name show-files] (->> files
                                    (remove :show)
                                    date-time-sort-partition)]
    (glom-show show-files)))




(defn album-mover
  "Returns a CommentUpdater for use with JVorbisComment,
   for updating comments in place,
   which moves any ALBUM tags to ARTIST, removing any
   ARTIST tags which might have been there.
   Necessary because liquidsoap is broken and won't save
   anything but artist in filename, and we've cleaned the artist
   to make it a legal unix filename, so the real show name with
   unescaped characters is stashed in ALBUM instead, and must be
   recovered here."
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



(defn bad-title?
  "Predicate for determining if a title needs to be replaced."
  [f]
  (let [n (.name f)
        v (.value f)]
    (and (= "TITLE" n)
         ;; TODO: bikeshed with (some? [#(pred) #(pred) empty?] (st/trim v))
         (or (= "Unknown" (st/trim v))
             (= "%Y-%m-%d" (st/trim v))
             (empty? v)))))


(defn title-fixer
  "Returns a CommentUpdater for use with JVorbisComment,
   for updating comments in place,
   which removes any bad TITLE tags and replaces
   them with the show date"
  [^String date]
  (reify CommentUpdater
    (updateComments [this comments]
      (boolean
       (let [fields (.fields comments)
             unknowns (doall (filter bad-title? fields))]
         (log/debug "fixing titles" fields)
         (when (not (empty? unknowns))
           (log/debug "got unknowns" unknowns)
           (doseq [u unknowns]
             (log/debug "title-fixer removing" u)
             (.remove fields u))
           (.add fields (CommentField. "TITLE" date))
           ;; return true if updated, false if not
           true))))))


(defn fix-in-place!
  "Utility function to do in-place comment updating,
   using the supplied CommentUpdater"
  [fpath ^CommentUpdater fixer]
  (log/debug "fixing files" fpath)
  (try
    (-> fpath
        jio/as-file
        (VorbisIO/writeComments fixer))
    (catch Exception e
      (log/error e))))

(defn transfer-comments!
  "Grab the comments from the first block in in-path, and tump them in out-path.
   Hack required when using ogg123/oggenc instead of thrashcat."
  [in-path out-path]
  (try
    (-> out-path
        jio/file
        (VorbisIO/writeComments (-> in-path jio/file VorbisIO/readComments)))
    (catch Exception e
      (log/error e in-path out-path))))



(defn fix-comments!
  "Takes a path to output files,
   and a fileglob (name, date, filenames),
   and executes album and title fixes for that output file.
   The output file had better be there already."
  [path {:keys [name date filenames]}]
  (let [out-path (format-show-save path name date)]
    (transfer-comments! (first filenames) out-path)
    (log/debug "fixing comments" out-path)
    (doseq [f [(title-fixer date) (album-mover)]]
      (fix-in-place! out-path f))))


(defn move-originals!
  "Takes a fileglob (name, date filenames) and the directory for backup files.
   Moves all the original filenames in the fileglob to the backup location."
  [{:keys [filenames]} backup-dir]
  (let [backup-dir (if (.endsWith backup-dir "/") backup-dir (str backup-dir "/"))]
    (doseq [f filenames]
      (log/debug "moving" f "to" backup-dir)
      (mv f backup-dir))))


(defn concatenate!
  "Takes a cmd-path (usually thrashcat) to a command which takes many streams on stdin and returns a concatenated single ogg/vorbis stream on stdout,
  a path to deposit the concatenated file, an out-commands-file to dump a temporary shell script,
  and a fileglob (name, date, filenames) with the info about a series of files to be concatenated.
  Executes the cmd-path, feeding the filenames into it. Dumps the output to a specially-named
  file in path."
  [cmd-path path out-commands-file fileglob]
  (let [cmd-line (gen-command-line cmd-path path fileglob)]
    (log/info cmd-line)
    (spit out-commands-file cmd-line))
  (try
    (let [{:keys [stdout stderr exit-code]} (bash out-commands-file {:verbose true})]
      (log/debug {:doing stdout, :result stderr, :status @exit-code})
      (when (not= 0 @exit-code)
        ;; TODO: throw exception too?
        (log/error {:doing stdout, :result stderr, :status @exit-code}))
      ;; TODO: rm the old out-commands-file
      )
    (catch Exception e
      (log/error e cmd-path path out-commands-file fileglob))))



(defn execute-all!
  "Takes a cmd-path to the external concatenating command,
  a path to dump concatenated files,
  an out-commands-file for temp scripts,
  a backup-dir for moving the original filenames to,
 and a fileglob (name, date, filenames) with the files to be concatenated.
  Concatenates the files, fixes comments, and moves the originals to the backup-dir.
  This is the core of the program."
  [{:keys [cmd-path out-oggs-path out-commands-file backup-dir]} files]
  [{:pre [(assert (every? (comp not empty?) [cmd-path backup-dir
                                             out-commands-file out-oggs-path]))]}]
  (doseq [f files]
    (log/info f)
    (concatenate! cmd-path out-oggs-path out-commands-file f)
    (fix-comments! out-oggs-path f)
    (move-originals! f backup-dir)))


(defn prepare-all
  "Takes list of files, and returns fileglobs (name, date, filenames) for them.
   Processes files both with and without valid shows in their names."
  [files]
  (concat (prepare-shows files) (without-shows files)))


(defn run-all!
  "Takes a config map, and runs the program, concatenating the oggs."
  [{:keys [in-oggs-path cmd-path out-oggs-path out-commands-file backup-dir] :as conf}]
  [{:pre [(every? (comp not empty?) [in-oggs-path cmd-path backup-dir
                                     out-commands-file out-oggs-path])]}]
  ;; TODO: check valid things, like all the directories actually exist!
  (->> in-oggs-path
       get-oggs
       (map (partial parse in-oggs-path))
       prepare-all
       (execute-all! conf))
  ;; TODO: out-oggs-path generate the feed!
  )




(defn process-config
  "Read and deserialize the config"
  [config-path]
  (->> config-path
       slurp
       edn/read-string))



(defn revision-info
  "Utility for determing the program's revision."
  []
  (let [{:keys [version revision]} (ujava/get-project-properties "defragment" "defragment")]
    (format "Version: %s, Revision %s" version revision)))


(defn -main [config-path & args]
  (log/info "Welcome to Defragment " (revision-info))
  (log/info "Loading config file " config-path)
  (let [conf (process-config config-path)]
    (run-all! conf)
    (log/info "concatenation done")
    (feed/make-feed! conf)
    (log/info "feed done")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment

  (-main "resources/test-config.edn")

  (clojure.tools.trace/trace-vars parse)

  (log/info "test")
  
  (log/set-level! :trace)



  )

