(defproject defragment "0.1.13"
  :description "Ogg file defragmenter"
  :url "https://gitlab.com/kenrestivo/defragment"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-bin "0.3.4"]]

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [me.raynes/conch "0.8.0"]
                 [utilza "0.1.72"]
                 [com.taoensso/nippy "2.10.0"] 
                 [org.clojure/data.xml "0.0.8"]
                 [clj-http "2.0.0"]
                 [cheshire "5.5.0"]
                 [com.taoensso/timbre "4.1.4"]
                 [clj-time "0.11.0"]
                 [org.adamb/jvorbiscomment "1.0.3"]
                 [org.clojure/tools.trace "0.7.8"]
                 [useful "0.8.8"]]
  :main  defragment.core
  :bin {:name "defragment"}
  ;; needed for this wacky jorbiscomment thing
  :repositories [["kens" "http://restivo.org/mvn"]]
  :profiles {:uberjar {:aot :all
                       :dependencies [[org.restivo/clojure "1.7.0-fastload"
                                       :exclusions [org.clojure/clojure]]]
                       :uberjar-name "defragment.jar"}
             :dev {:dependencies [[org.timmc/handy "1.7.0" :exclusions [[org.clojure/clojure]]]]}})
