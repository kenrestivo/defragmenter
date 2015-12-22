(ns defragment.feed
  (:require [taoensso.timbre :as log]
            [clojure.data.xml :as xml]
            [defragment.hubzilla :as hubzilla]
            [utilza.misc :as umisc]
            [taoensso.nippy :as nippy]
            [me.raynes.conch  :as sh]
            [me.raynes.conch.low-level :as lsh]
            [utilza.repl :as urepl]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clj-time.core :as time]
            [defragment.utils :as utils]
            [clojure.string :as st]
            [utilza.misc :as umisc]
            [utilza.java :as ujava]
            [utilza.file :as file]
            [clojure.java.io :as jio]
            [clj-time.format :as time-fmt]
            [clj-time.coerce :as ctime]
            [clojure.string :as s]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [adamb.vorbis VorbisCommentHeader VorbisIO CommentField CommentUpdater]
           [java.lang.Math]
           [org.joda.DateTime]))


(defn date-attempt
  [{:keys [date]}]
  (try
    (java.util.Date. date)
    (catch Exception e
      (log/warn e))))

(defn my-zone
  "show a time in a readable format, in my damn time zone
    from utilza"
  [t]
  (time-fmt/unparse
   (time-fmt/formatters :rfc822)
   t))

;; is this OK?
(defn ymd-to-date [ymd]
  (try
    (->> ymd
         (map #(Integer/parseInt %))
         (apply time/local-date-time) ;; nope, these should be america/la!
         ctime/to-date-time
         my-zone)
    (catch Exception e
      (log/error e))))


(defn s->h:m:s
  [s-str]
  ;; should be a reduce!
  (let [ss  (Float/parseFloat s-str)
        h (int (Math/floor (/ ss 3600)))
        r (mod ss 3600)
        m (int (Math/floor (/ r 60)))
        s (int (mod r 60))]
    (format "%02d:%02d:%02d" h m s)))


(defn unogg
  "Strip the .suffix from a string.
   WARNING: Can not handle . in the name!"
  [s]
  (-> s
      (st/split  #"\.ogg")
      first))


(defn get-duration
  [python-path duration-path full-path]
  (log/debug "getting duration for" full-path)
  (binding [sh/*throw* false]
    (try
      (-> (lsh/proc python-path duration-path full-path)
          :out
          jio/reader
          line-seq
          first
          s->h:m:s)
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
  [rss-base-url dirpath python-path duration-path f]
  (let [[y m d show & _ ] (st/split f #"-")
        full-file (str dirpath "/" f)]
    (log/trace "processing" full-file "->" show)
    (merge {:date (ymd-to-date [y m d])
            :show (if (-> show empty?) "" (unogg show))
            :basename f
            :length (-> full-file jio/file .length)
            :duration (get-duration python-path duration-path full-file)
            :link (str rss-base-url f)
            :full-file full-file}
           (read-header full-file))))



(defn process-dir!
  [rss-base-url  db-path python-path duration-path hubzilla dirpath]
  (log/debug "processing dir" rss-base-url db-path dirpath)
  (let [db (try (-> db-path ujava/slurp-bytes nippy/thaw)
                (catch Exception e
                  (log/error e)
                  []))
        new-db (for [f (file/file-names dirpath  #".*?\.ogg")]
                 (do
                   (log/trace "checking db for file" f)
                   (if-let [found (->> db (filter #(= (:basename %) f)) first)]
                     (do (log/trace "found" found) found)
                     (let [munged (process-file rss-base-url dirpath python-path duration-path f)]
                       (assoc munged :post-future (future (try
                                                            (hubzilla/post-to-hubzilla hubzilla munged)
                                                            (catch Exception e
                                                              (log/error e)))))))))]
    (-> (map #(dissoc % :post-future) new-db)
        nippy/freeze
        (clojure.java.io/copy (java.io.File. db-path)))
    new-db))



(defn get-files
  [{:keys [rss-base-url db-path python-path duration-path hubzilla out-oggs-path]}]
  (log/info "getting files" rss-base-url out-oggs-path db-path)
  (->>   (process-dir! rss-base-url db-path python-path duration-path  hubzilla out-oggs-path)
         (sort-by date-attempt)
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
                 (xml/element :link {}  link)
                 (xml/element :comments {}  "http://spaz.org/radio")
                 (xml/element :pubDate {}
                              date)
                 (xml/element :dc:creator {} (umisc/escape-html (or artist "Unknown")))
                 (xml/element :category {} (xml/cdata "[music]"))
                 (xml/element :guid {:isPermaLink "false"}
                              link)
                 (xml/element :description {}
                              (xml/cdata full-title))
                 (xml/element :content:encoded {}
                              (xml/cdata (format "<p>%s</p><audio controls src='%s'>Player</audio>" (umisc/escape-html full-title) link)))
                 (xml/element :wfw:commentRss {} "http://spaz.org/radio")
                 (xml/element :slash:comments {} 0)
                 (xml/element :enclosure  {:type "audio/ogg"
                                           :url link
                                           :length length})
                 (xml/element :itunes:duration {}
                              duration )
                 (xml/element :itunes:subtitle {}
                              (umisc/escape-html full-title))
                 (xml/element :itunes:summary {}
                              (umisc/escape-html full-title))
                 (xml/element :itunes:keywords {}
                              "spaz, music" )
                 (xml/element :itunes:author {}
                              "spaz")
                 (xml/element :itunes:explicit {}
                              "no")
                 (xml/element :itunes:block {}
                              "no"             ))))




(defn xml-feedify
  [rss-file-self items]
  (let [last-date (-> items first :date)
        formatted-items (map format-item items)
        feed-title "SPAZ Radio Archives"]
    (xml/emit-str
     (xml/element
      :rss
      {:version "2.0"
       :xmlns:content "http://purl.org/rss/1.0/modules/content/"
       :xmlns:wfw "http://wellformedweb.org/CommentAPI/"
       :xmlns:dc "http://purl.org/dc/elements/1.1/"
       :xmlns:atom "http://www.w3.org/2005/Atom"
       :xmlns:sy "http://purl.org/rss/1.0/modules/syndication/"
       :xmlns:slash "http://purl.org/rss/1.0/modules/slash/"
       :xmlns:itunes "http://www.itunes.com/dtds/podcast-1.0.dtd"
       :xmlns:media "http://search.yahoo.com/mrss/"
       }
      (apply xml/element :channel {}
             (xml/element :title {} feed-title)
             (xml/element :atom:link
                          {:href rss-file-self
                           :rel "self"
                           :type "application/rss+xml"})
             (xml/element :link {} "http://spaz.org/radio")
             (xml/element :description {} "SPAZ Radio Archives")
             (xml/element :lastBuildDate {} last-date)
             (xml/element :language {}
                          "en")             
             (xml/element :sy:updatePeriod  {}
                          "daily")
             (xml/element :sy:updateFrequency {}
                          1)
             (xml/element :generator {}
                          "http://spaz.org")
             (xml/element :copyright {}
                          "sharenjoy")
             (xml/element :webMaster {}
                          "info@spaz.org (S.P.A.Z. Infoline)")
             (xml/element :ttl {}
                          1440)
             (xml/element :image {}
                          [(xml/element :url {} "http://spaz.org/images/spazradioicon-0.jpg" )
                           (xml/element :title  {} feed-title)
                           (xml/element :link  {} "http://spaz.org/radio")
                           (xml/element :width  {} "144")
                           (xml/element :height  {} "112")])
             (xml/element :itunes:subtitle {} "SPAZ radio archives")
             (xml/element  :itunes:summary {} "SPAZ radio archives")
             (xml/element :itunes:keywords {} "music")
             (xml/element :itunes:category {:text "Music"})
             (xml/element :itunes:author {} "SPAZ")
             (xml/element :itunes:owner {} 
                          [(xml/element :itunes:name {}  "SPAZ")
                           (xml/element :itunes:email {} "info@spaz.org (infoline)" )])
             (xml/element :itunes:block {} "no")
             (xml/element :itunes:explicit {} "no")
             (xml/element :itunes:image
                          {:href "http://spaz.org/radio"})
             formatted-items)))))

(defn make-feed!
  [{:keys [out-oggs-path in-oggs-path rss-base-url db-path rss-self-url
           rss-out-file python-path duration-path hubzilla] :as settings}]
  {:pre [(every? (comp not nil?) [out-oggs-path in-oggs-path db-path
                                  rss-base-url rss-self-url rss-out-file python-path
                                  duration-path hubzilla])]}
  (log/info "making feed" out-oggs-path rss-base-url rss-self-url " --> " rss-out-file)
  (let [files (get-files settings)]
    (->> files
         (map #(dissoc % :post-future))
         (xml-feedify rss-self-url)
         (spit rss-out-file))
    ;; wait for all the threads to stop
    (doseq [f files]
      (-> f :post-future dissoc))))





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
  

  (->> "/mnt/sdcard/tmp/livedef.nip" ujava/slurp-bytes nippy/thaw
       (urepl/massive-spew "/tmp/foo.edn")  )

  (->> "/mnt/sdcard/tmp/livedef.nip" ujava/slurp-bytes nippy/thaw
       (sort-by date-attempt)
       reverse
       (urepl/massive-spew "/tmp/foo.edn"))
  
  )
