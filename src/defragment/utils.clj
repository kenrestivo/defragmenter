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
