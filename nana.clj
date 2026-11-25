(ns nana
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

(defn run-bash-cmd [{:keys [cmd]}]
  (let [{:keys [out err]} (sh/sh "bash" "-lc" cmd)]
    (str out err)))

(def run-bash-cmd-tool
  {:type "function"
   :function {:name "run-bash-cmd"
              :parameters {:type "object"
                           :properties {:cmd {:type "string"}}
                           :required ["cmd"]}}})

(defn run-tool-call [{:keys [id function]}]
  {:role "tool"
   :tool_call_id "id"
   :content ((requiring-resolve (symbol "nana" (:name function)))
             (json/parse-string (:arguments function) true))})

(defn chat [{:keys [api-endpoint base-url api-key api-key-env model reasoning-effort]} messages]
  (http/post
   (or api-endpoint (str "https://" base-url "/v1/chat/completions"))
   {:headers {:Authorization (str "Bearer " (or api-key (System/getenv api-key-env)))}
    :cookie-policy :none
    :content-type :json
    :as :json
    :form-params {:model model
                  :reasoning_effort reasoning-effort
                  :tools [run-bash-cmd-tool]
                  :messages messages}}))

(def cli-options
  [["-a" "--api-endpoint API_ENDPOINT"]
   ["-b" "--base-url BASE_URL" :default "openrouter.ai/api"]
   ["-k" "--api-key API_KEY"]
   ["-e" "--api-key-env API_KEY_ENV" :default "OPENROUTER_API_KEY"]
   ["-m" "--model MODEL" :default "openrouter/free"]
   ["-r" "--reasoning-effort REASONING_EFFORT" :default "max"]
   ["-h" "--help" "Show help"]])

(defn usage [summary]
  (str "Usage: clj -M -m nana [options] <prompt>\n\nOptions:\n" summary))

(def system-message
  {:role "system"
   :content "You are bash specialist. Echo exactly the same back to simple greetings."})

(defn run-agent [prompt options]
  (loop [messages [system-message {:role "user" :content prompt}]]
    (let [message (-> messages ((partial chat options)) :body :choices first :message)
          tool-calls (:tool_calls message)]
      (if (not (seq tool-calls))
        (do (println (:content message)))
        (recur (into (conj messages message) (mapv run-tool-call tool-calls)))))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        prompt (str/join " " arguments)]
    (cond
      (:help options)
      (do (println (usage summary))
          (System/exit 0))
      errors
      (do (binding [*out* *err*]
            (println (str/join "\n" errors))
            (println)
            (println (usage summary)))
          (System/exit 2))
      (str/blank? prompt)
      (do (binding [*out* *err*]
            (println "Error: <prompt> is required.\n")
            (println (usage summary)))
          (System/exit 2)))
    (run-agent prompt options))
  (System/exit 0))
