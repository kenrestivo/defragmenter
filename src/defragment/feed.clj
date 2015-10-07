(ns defragment.feed
  (:require [taoensso.timbre :as log]
            [clojure.data.xml :as xml]
            [utilza.misc :as umisc]
            [me.raynes.conch  :as sh]
            [utilza.repl :as urepl]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clj-time.core :as time]
            [defragment.utils :as utils]
            [clojure.string :as st]
            [utilza.misc :as umisc]
            [utilza.file :as file]
            [clojure.java.io :as jio]
            [clj-time.format :as time-fmt]
            [clj-time.coerce :as ctime]
            [clojure.string :as s]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [adamb.vorbis VorbisCommentHeader VorbisIO CommentField CommentUpdater]
           [org.joda.DateTime]))

(sh/programs ogginfo)

(defn my-zone
  "show a time in a readable format, in my damn time zone
    from utilza"
  [t]
  (time-fmt/unparse
   (time-fmt/formatters :rfc822)
   t))

;; is this OK?
(defn ymd-to-date [ymd]
  (->> ymd
       (map #(Integer/parseInt %))
       (apply time/local-date-time)
       ctime/to-date-time
       my-zone))


(defn m:s->h:m:s
  [ms]
  (let [[_ m s] (re-matches #"(.*)m:(.*)" ms)
        mm (Integer/parseInt m)
        ss (Integer/parseInt s)]
    (format "%02d:%02d:%02d" (int (/ mm 60)) (mod mm 60) ss)))

(defn unogg
  "Strip the .suffix from a string.
   WARNING: Can not handle . in the name!"
  [s]
  (-> s
      (st/split  #"\.ogg")
      first))


(defn get-duration
  [full-path]
  (log/debug "getting duration for" full-path)
  (binding [sh/*throw* false]
    (try
      (->> full-path
           ogginfo
           string/split-lines
           (filter #(.contains % "Playback length"))
           first
           (re-matches #"\tPlayback length:\s+(.+)\..*")
           last
           m:s->h:m:s)
      (catch Exception e
        (log/error e)
        "04:00:00"))))


(defn read-header
  [f]
  (try
    (-> f
        jio/file
        VorbisIO/readComments
        utils/header->map)
    (catch Exception e
      (log/error e))))


(defn process-file
  [link dirpath f]
  (let [[y m d show & _ ] (st/split f #"-")
        full-file (str dirpath "/" f)]
    (log/trace "processing" full-file "->" show)
    (merge {:date (ymd-to-date [y m d])
            :show (if (-> show empty?) "" (unogg show))
            :basename f
            :length (-> full-file jio/file .length)
            :duration (get-duration full-file)
            :link (str link f)
            :full-file full-file}
           (read-header full-file))))



(defn process-dir
  [link  db-path dirpath]
  (log/debug "processing dir" link db-path dirpath)
  (let [db (try (-> db-path slurp edn/read-string)
                (catch Exception e
                  (log/error e)
                  []))
        new-db (for [f (file/file-names dirpath  #".*?\.ogg")]
                 (if-let [found (->> db (filter #(= (:basename %) f)) first)]
                   (do
                     (log/trace "found" found)
                     found)
                   (process-file link dirpath f)))]
    ;;(log/trace "old db" db)
    ;;(log/trace "new db" new-db)
    (.write *out* "flushing")
    (.flush *out*)
    (urepl/massive-spew db-path new-db)
    new-db))



(defn get-files
  [link db-path dirname]
  (log/info "getting files" link dirname db-path)
  (->>   (process-dir link db-path dirname)
         (sort-by :title)
         reverse))

;; TODO: move to utlilza
(defn unogg2
  "Strip the .suffix from a string.
   WARNING: Can not handle . in the name!"
  [s]
  (if s
    (st/join (butlast (st/split s  #"\.")))
    ""))


(defn format-item
  "logentry comes in from the view as :key date, :id guid, :value message.
   Change these to XML item elements for RSS feed."
  [{:keys [file show date link date duration title artist length]}]
  (let [full-title (umisc/inter-str " - " [artist title])]
    (xml/element :item {}
                 (xml/element :title {}
                              full-title)
                 (xml/element :description {}
                              full-title)
                 (xml/element :content:encoded {}
                              )
                 (xml/element :enclosure:url {:type "audio/ogg"
                                              :length length}
                              link)
                 (xml/element :itunes:duration {}
                              duration )
                 (xml/element :itunes:subtitle {}
                              )
                 (xml/element :itunes:summary {}
                              )
                 (xml/element :itunes:keywords {}
                              "spaz, music" )
                 (xml/element :itunes:author {}
                              "spaz")
                 (xml/element :itunes:explicit {}
                              "no")
                 (xml/element :itunes:block {}
                              "no"             )
                 (xml/element :pubDate {}
                              date)
                 (xml/element :link {}  link)
                 (xml/element :guid {:isPermaLink "false"}
                              link))))



(defn xml-feedify
  [rss-file-self items]
  (let [last-date (-> items first :date)
        formatted-items (map format-item items)]
    (xml/emit-str
     (xml/element
      :rss
      {:version "2.0"
       :xmlns:atom "http://www.w3.org/2005/Atom"
       :xmlns:content "http://purl.org/rss/1.0/modules/content/"
       :xmlns:wfw "http://wellformedweb.org/CommentAPI/"
       :xmlns:dc "http://purl.org/dc/elements/1.1/"
       :xmlns:sy "http://purl.org/rss/1.0/modules/syndication/"
       :xmlns:slash "http://purl.org/rss/1.0/modules/slash/"
       :xmlns:itunes "http://www.itunes.com/dtds/podcast-1.0.dtd"
       :xmlns:media "http://search.yahoo.com/mrss/"
       }
      (apply xml/element :channel {}
             (xml/element :title {} "SPAZ Radio Archives")
             (xml/element :link {} "http://spaz.org/radio")
             (xml/element :sy:updatePeriod  {}
                          "daily")
             (xml/element :sy:updateFrequency {}
                          1)
             (xml/element :copyright {}
                          "sharenjoy")
             (xml/element :webMaster {}
                          "info@spaz.org (S.P.A.Z. Infoline)")
             (xml/element :language {}
                          "en")
             (xml/element :generator {}
                          "http://spaz.org")
             (xml/element :ttl {}
                          1440)
             (xml/element :description {} "SPAZ Radio Archives")
             (xml/element :lastBuildDate {} last-date)
             (xml/element :atom:link
                          {:href rss-file-self
                           :rel "self"
                           :type "application/rss+xml"})
             formatted-items)))))

(defn make-feed!
  [{:keys [out-oggs-path rss-base-url db-path rss-self-url rss-out-file]}]
  {:pre [(every? (comp not nil?) [out-oggs-path db-path rss-base-url rss-self-url rss-out-file])]}
  (log/info "making feed" out-oggs-path rss-base-url rss-self-url " --> " rss-out-file)
  (->> out-oggs-path
       (get-files rss-base-url db-path)
       (xml-feedify rss-self-url)
       (spit rss-out-file)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(comment

  ;; TODO: get the files, get list of shows in it, get all the feeds for those shows
  

  (log/set-level! :trace)

  (log/set-level! :info)
  
  (log/info "testing")
  
  
  (urepl/massive-spew "/tmp/foo.edn" *1)

  (-> "resources/test-config.edn"
      slurp
      edn/read-string
      make-feed!)
  

  (let [{:keys [db-path out-oggs-path rss-base-url]}  (-> "resources/test-config.edn"
                                                          slurp
                                                          edn/read-string)]
    (->> (get-files rss-base-url db-path out-oggs-path )
         (urepl/massive-spew "/tmp/foo.edn")))

  (let [{:keys [db-path out-oggs-path rss-base-url]}  (-> "resources/test-config.edn"
                                                          slurp
                                                          edn/read-string)]
    (get-files rss-base-url db-path out-oggs-path ))
  

  
  )
