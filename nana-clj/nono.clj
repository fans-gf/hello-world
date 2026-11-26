(ns nono
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.shell :as sh]))

(defn chat [messages]
  (http/post
   "https://openrouter.ai/api/v1/chat/completions"
   {:headers {:Authorization (str "Bearer " (System/getenv "OPENROUTER_API_KEY"))}
    :cookie-policy :none
    :content-type :json
    :as :json
    :form-params {:model "openrouter/free"
                  :tools [{:type "function"
                           :function {:name "run-bash-cmd"
                                      :parameters {:type "object"
                                                   :properties {:cmd {:type "string"}}
                                                   :required ["cmd"]}}}]
                  :messages messages}}))

(defn run-tool-call [{:keys [id] {:keys [name arguments]} :function}]
  (let [{:keys [out err]} (sh/sh "bash" "-lc" (:cmd (json/parse-string arguments true)))]
    {:role "tool"
     :tool_call_id id
     :content (str out err)}))

(defn -main [& [prompt]]
  ((if (empty? prompt) (System/exit 2))
   (loop [messages [{:role "system" :content "You are bash specialist. Echo exactly the same back to simple greetings."}
                    {:role "user" :content prompt}]]
     (let [message (-> messages chat :body :choices first :message)
           tool-calls (:tool_calls message)]
       (if (not (seq tool-calls))
         (-> message :content println)
         (recur (into (conj messages message) (mapv run-tool-call tool-calls))))))
   (System/exit 0)))
