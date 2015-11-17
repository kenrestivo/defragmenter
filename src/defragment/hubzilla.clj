(ns defragment.hubzilla
  (:require [clj-http.client :as client]
            [clojure.edn :as edn]
            [defragment.utils :as utils]
            [taoensso.timbre :as log]))


(defn  post-to-hubzilla
  [{:keys [url login pw channel listen]}
   {:keys [basename date encoder duration artist title link length] :as rec}]
  (log/info "sending to hubzilla" basename)
  (if (every? (comp not empty?) [date duration artist title link])
    (when-let [{:keys [body headers]}
               (try
                 (client/post url
                              {:basic-auth [login pw]
                               :throw-entire-message? true
                               :as :json
                               :retry-handler utils/retry
                               :form-params {:title (format "%s - %s" title artist)
                                             :status (format "%s - %s\n %s\n[audio]%s[/audio]"
                                                             title artist duration link)}})
                 (catch Exception e
                   (log/error e)))]
      (log/debug "sent to hubzilla" body  " --> " headers))
    (do
      (log/error rec)
      (throw (Exception. "missing keys")))))




(defn post-all
  [settings items]
  (doseq [item items]
    (when-not (-> item :title empty?)
      (try
        (post-to-hubzilla settings item)
        (catch Exception e
          (log/error e))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(comment

  (let [{:keys [out-oggs-path rss-base-url db-path rss-self-url rss-out-file
                python-path duration-path hubzilla]}
        (-> "/home/cust/spaz/src/fake-rss.edn"
            slurp
            edn/read-string)]
    (->> out-oggs-path
         (get-files rss-base-url db-path python-path duration-path)
         (hubzilla/post-all hubzilla)))
  




  )
