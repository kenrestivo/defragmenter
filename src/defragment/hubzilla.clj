(ns defragment.hubzilla
  (:require [clj-http.client :as client]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]))


(defn  post-to-hubzilla
  [{:keys [url login pw channel listen]} {:keys [basename date encoder duration artist title link length]}]
  {:pre [(every? (comp not empty?) [date duration artist title link])]}
  (log/trace "sending to hubzilla" basename)
  (let [{:keys [body headers]}
        (client/post url
                     {:basic-auth [login pw]
                      :throw-entire-message? true
                      :as :json
                      :form-params {:title (format "%s - %s" title artist)
                                    :status (format "%s - %s\n %s\n[audio]%s[/audio]"
                                                    title artist duration link)}})]
    (log/trace "sent to hubzilla" body  " --> " headers)))




(defn post-all
  [settings items]
  (doseq [item items]
    (when-not (-> item :title empty?)
      (try
        (post-to-hubzilla settings item)
        (catch Exception e
          (log/error e))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
