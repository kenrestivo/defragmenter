(defproject defragment "0.1.1"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-bin "0.3.4"]]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [me.raynes/conch "0.8.0"]
                 [utilza "0.1.60"]
                 [com.taoensso/timbre "3.3.1"]
                 [useful "0.8.4"]]
  :main  defragment.core
  :bin {:name "defragment"}
  :profiles {:uberjar {:aot :all}})
