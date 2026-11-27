;;; Usage: BASE_URL=yours API_KEY_ENV=yours MODEL=yours clj -M -m noob
;;;    or, BASE_URL=yours API_KEY=yours API_KEY_ENV=API_KEY MODEL=yours clj -M -m noob

(ns noob
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.shell :as sh]))

(def base-url (System/getenv "BASE_URL"))
(def api-key-env (System/getenv "API_KEY_ENV"))
(def model (System/getenv "MODEL"))

(defn run-tool-call [{:keys [id] {:keys [name arguments]} :function}]
  (let [{:keys [out err]} (sh/sh "bash" "-lc" (:cmd (json/parse-string arguments true)))]
    {:role "tool"
     :tool_call_id id
     :content (str out err)}))

(def run-bash-cmd-tool
  {:type "function"
   :function {:name "run-bash-cmd"
              :parameters {:type "object"
                           :properties {:cmd {:type "string"}}
                           :required ["cmd"]}}})

(defn chat [messages]
  (http/post
   (str "https://" base-url "/v1/chat/completions")
   {:headers {:Authorization (str "Bearer " (System/getenv api-key-env))}
    :cookie-policy :none
    :content-type :json
    :decompress-body false
    :as :json
    :form-params {:model model
                  :tools [run-bash-cmd-tool]
                  :messages messages}}))

(defn input []
  (print "> ")
  (flush)
  (read-line))

(defn run-agent [messages]
  (loop [messages+user (let [user-message {:role "user" :content (input)}]
                         (conj messages user-message))]
    (let [assistant-message (-> messages+user chat :body :choices first :message)
          messages+user+assistant (conj messages+user assistant-message)
          {:keys [tool_calls content]} assistant-message]
      (if-let [no-tool-calls? (-> tool_calls seq not)]
        (do (println "<" content)
            messages+user+assistant)
        (let [tools-messages (mapv run-tool-call tool_calls)
              messages+user+assistant+tools (into messages+user+assistant tools-messages)]
          (recur messages+user+assistant+tools))))))

(def system-message
  {:role "system"
   :content "You are bash specialist. Echo exactly the same back to simple greetings."})

(defn -main []
  (println "[Press ctrl-c to stop.]")
  (loop [messages [system-message]]
    (recur (run-agent messages))))
