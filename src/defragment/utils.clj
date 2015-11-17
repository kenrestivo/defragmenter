(ns defragment.utils
  (:import [adamb.vorbis VorbisCommentHeader VorbisIO CommentField CommentUpdater])
  (:require [me.raynes.conch :refer [programs with-programs let-programs] :as csh]
            [me.raynes.conch.low-level :as sh]
            [utilza.repl :as urepl]
            [clojure.tools.trace :as trace]
            [useful.map :as um]
            [utilza.java :as ujava]
            [clojure.edn :as edn]
            [clojure.java.io :as jio]
            [utilza.file :as file]
            [utilza.misc :as umisc]
            [taoensso.timbre :as log]
            [clojure.string :as st]))

;;; XXX quick hack 
;;; TODO:  thread the settings through and use make-retry-fn
(def max-retries 5)
(def retry-wait 5000)



(defn comments->vec
  "Takes array of CommentFields and translates to a proper Clojure vector"
  [hs]
  (for [^CommentField h hs]
    [(-> h .name st/lower-case keyword) (.value h)]))

(defn header->map
  [^VorbisCommentHeader h]
  (->> h
       .fields
       comments->vec
       (into {})))


;;; XXX hack, don't use, use make-retry-fn and thread settings through
(defn retry
  [ex try-count http-context]
  (log/warn ex http-context)
  (Thread/sleep (* try-count retry-wait))
  ;; TODO: might want to try smaller chunks too!
  (if (> try-count max-retries) 
    false
    (log/error ex try-count http-context)))
