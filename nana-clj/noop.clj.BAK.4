(ns noop
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

(defn run-bash-cmd [{:keys [cmd]}]
  (let [{:keys [exit out err]} (sh/sh "bash" "-lc" cmd)]
    (if (zero? exit)
      (str out err)
      (str "exit_code=" exit "\n" out err))))

(def run-bash-cmd-tool
  {:type "function"
   :function {:name "run-bash-cmd"
              :parameters {:type "object"
                           :properties {:cmd {:type "string"}}
                           :required ["cmd"]}}})

(def ->kebab-kw (memoize csk/->kebab-case-keyword))
(def ->snake-str (memoize csk/->snake_case_string))

(defn run-tool-call [{:keys [id] {:keys [name arguments]} :function}]
  {:role "tool"
   :tool-call-id id
   :content ((requiring-resolve (symbol "noop" name))
             (json/parse-string arguments ->kebab-kw))})

(defn chat [{:keys [api-endpoint base-url api-key api-key-env model reasoning-effort]}
            messages]
  (let [{:keys [body]}
        (http/post
         (or api-endpoint (str "https://" base-url "/v1/chat/completions"))
         {:headers {:Authorization (str "Bearer " (or api-key (System/getenv api-key-env)))}
          :cookie-policy :none
          :content-type :json
          :json-opts {:key-fn ->snake-str}
          :form-params {:model model
                        :reasoning-effort reasoning-effort
                        :tools [run-bash-cmd-tool]
                        :messages messages}})]
    (json/parse-string body ->kebab-kw)))

(def system-message
  {:role "system"
   :content "You are bash specialist. Echo exactly the same back to simple greetings."})

(defn input []
  (print "> ")
  (flush)
  (read-line))

(defn run-agent [options]
  (loop [messages [system-message
                   {:role "user"
                    :content (or (:prompt options) (input))}]]
    (let [message (-> messages ((partial chat options)) :choices first :message)
          tool-calls (:tool-calls message)]
      (if (not (seq tool-calls))
        (println "<" (:content message))
        (recur (into (conj messages message) (mapv run-tool-call tool-calls)))))))

(def cli-options
  [["-p" "--prompt PROMPT" "chat once."]
   ["-a" "--api-endpoint API_ENDPOINT"]
   ["-b" "--base-url BASE_URL" :default "openrouter.ai/api"]
   ["-k" "--api-key API_KEY"]
   ["-e" "--api-key-env API_KEY_ENV" :default "OPENROUTER_API_KEY"]
   ["-m" "--model MODEL" :default "openrouter/free"]
   ["-r" "--reasoning-effort REASONING_EFFORT" :default "max"]
   ["-h" "--help"]])

(defn -main [& args]
  (let [{:keys [summary options errors]} (cli/parse-opts args cli-options)
        usage (str "Run nano agent loop on chat completion endpoint.\n\n"
                   "Usage: clj -M -m noop [options]\n\nOptions:\n"
                   summary)]
    (cond
      (:help options)
      (do (println usage)
          (System/exit 0))
      errors
      (binding [*out* *err*]
        (println (str/join "\n" errors))
        (println)
        (println usage)
        (System/exit 2))
      (:prompt options)
      (do (run-agent options)
          (System/exit 0)))
    (println "[Press ctrl-c to stop.]")
    (while true (run-agent options))))
