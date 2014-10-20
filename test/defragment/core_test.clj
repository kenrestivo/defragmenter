(ns defragment.core-test
  (:require [clojure.test :refer :all]
            [utilza.repl :as urepl]
            [defragment.core :refer :all]))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))

(comment

  (->> "resources/test-config.edn"
       process-config
       :in-oggs-path
       get-oggs
       (map parse)
       (urepl/massive-spew "/tmp/foo.edn"))

  ;;; raw shows
  (->> "resources/test-config.edn"
       process-config
       :in-oggs-path
       get-oggs
       (map parse)
       (sort-and-partition :show )
       (urepl/massive-spew "/tmp/foo.edn"))

  (->> "resources/test-config.edn"
       process-config
       :in-oggs-path
       get-oggs
       (map parse)
       prepare-shows
       (urepl/massive-spew "/tmp/foo.edn"))
  
  ;; everything
  (->> "resources/test-config.edn"
       process-config
       :in-oggs-path
       get-oggs
       (map parse)
       prepare-all
       (urepl/massive-spew "/tmp/foo.edn"))
  
  

  (->> "resources/test-config.edn"
       process-config
       run-all)

  ;; only those without shows
  (->> "resources/test-config.edn"
       process-config
       :in-oggs-path
       get-oggs
       (map parse)
       without-shows
       (urepl/massive-spew "/tmp/foo.edn")) 

  )


(comment
  
  (->> "resources/test-config.edn"
       process-config
       :in-oggs-path
       get-oggs
       (map parse)
       (concat-shows! "/mnt/sdcard/tmp"))

  )

(comment
  (<  20141011231803 20141011225718)
  (< 20141011225757  20141011225809)

  )


(comment

    
  (->> "/mnt/sdcard/tmp/2014-10-11-test_in_the_dark_illegal_chars.ogg"
       jio/as-file
       VorbisIO/readComments
       .fields
       header->vec)


  


  
  
  (urepl/hjall *1)


  (->> "/mnt/sdcard/tmp/2014-10-02-.ogg"
       jio/as-file
       VorbisIO/readComments
       .fields)

  
  (->> "/mnt/sdcard/tmp/2014-10-02-.ogg"
       jio/as-file
       VorbisIO/readComments
       .fields
       (filter bad-title))


  
  (->> "/mnt/sdcard/tmp/2014-10-02-.ogg"
       jio/as-file
       VorbisIO/readComments
       .fields
       (map #(.name %)))
  
  (def foo *1)

  

  (->> "/mnt/sdcard/tmp/2014-09-28-.ogg"
       jio/as-file
       VorbisIO/readComments
       .fields
       (filter bad-title))

  
  (fix-in-place "/mnt/sdcard/tmp/2014-10-02-.ogg" (title-fixer "2014-10-02"))

  ;; for testing
  (fix-in-place "/mnt/sdcard/tmp/2014-10-02-.ogg" 
                (reify CommentUpdater
                  (updateComments [this comments]
                    (let [fields (.fields comments)]
                      (.add fields (CommentField. "ALBUM" "this is a Test Artist in Album yeah."))
                      true))))

  (fix-in-place "/mnt/sdcard/tmp/2014-10-02-.ogg" (album-mover))

  )