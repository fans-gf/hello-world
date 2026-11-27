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

(defn chat [{:keys [api-endpoint base-url api-key api-key-env model reasoning-effort user-agent]}
            messages]
  (let [req-body {:model model
                  :reasoning-effort reasoning-effort
                  :tools [run-bash-cmd-tool]
                  :messages messages}
        req-body-str (json/generate-string req-body {:key-fn ->snake-str})
        resp (http/post
              (or api-endpoint (str "https://" base-url "/v1/chat/completions"))
              {:headers
               {:Authorization (str "Bearer " (or api-key (System/getenv api-key-env)))
                :User-Agent user-agent}
               :cookie-policy :none
               :content-type :json
               :accept :json
               :decompress-body false
               :body req-body-str})
        resp-body-str (:body resp)]
    (json/parse-string resp-body-str ->kebab-kw)))

(def system-message
  {:role "system"
   :content "You are bash specialist. 
             - Echo exactly the same back to simple greetings.
             - Prefer to use `stdin stdout stderr` for non-complex tasks.
             - Always use `/tmp` as default work space rather than the current directory.
             - Don't download or install anything before consult with me for permission.
             - Forbidden from `sudo`-ing anyway.
             - Forbidden from `rm`-ing anything anyway."})

(defn input []
  (print "> ")
  (flush)
  (read-line))

(defn run-agent [options messages]
  (loop [counter 0
         messages+user (let [user-message {:role "user" :content (or (:prompt options) (input))}]
                         (conj messages user-message))]
    (let [{:keys [max-turns]} options
          is-finite-max-turns? (pos? max-turns)
          is-reached-max-turns? (== counter max-turns)]
      (when (and is-finite-max-turns? is-reached-max-turns?)
        (println "Warning: the task is NOT finished within max turns" max-turns ", exit!")
        (System/exit 2)))
    (let [assistant-message (-> messages+user ((partial chat options)) :choices first :message)
          messages+user+assistant (conj messages+user assistant-message)
          {:keys [tool-calls content]} assistant-message]
      (if-let [no-tool-calls? (not (seq tool-calls))]
        (do (println "<" content)
            messages+user+assistant)
        (let [tools-messages (mapv run-tool-call tool-calls)
              messages+user+assistant+tools (into messages+user+assistant tools-messages)]
          (recur (inc counter) messages+user+assistant+tools))))))

(def cli-options
  [["-p" "--prompt PROMPT" "chat once."]
   ["-a" "--api-endpoint API_ENDPOINT"]
   ["-b" "--base-url BASE_URL" :default "openrouter.ai/api"]
   ["-k" "--api-key API_KEY"]
   ["-e" "--api-key-env API_KEY_ENV" :default "OPENROUTER_API_KEY"]
   ["-m" "--model MODEL" :default "openrouter/free"]
   ["-r" "--reasoning-effort REASONING_EFFORT" :default "max"]
   ["-u" "--user-agent USER_AGENT" :default "pi/1.6.1"]
   ["-n" "--max-turns MAX_TRUNS" "integer, non-positive as infinite." :default "0"]
   ["-h" "--help"]])

(defn -main [& args]
  (let [{:keys [summary options errors]} (cli/parse-opts args cli-options)
        usage (str "Run nano agent loop on chat completion endpoint.\n\n"
                   "Usage: clj -M -m noop [options]\n\nOptions:\n"
                   summary)
        max-turns-str (:max-turns options)
        max-turns (parse-long max-turns-str)]
    (when (:help options)
      (println usage)
      (System/exit 0))
    (let [invalid-max-turns? (not max-turns)]
      (if (or errors invalid-max-turns?)
        (binding [*out* *err*]
          (if errors (println (str/join "\n" errors)))
          (if invalid-max-turns? (println "Not an integer:" max-turns-str))
          (println)
          (println usage)
          (System/exit 2))))
    (let [options' (assoc options :max-turns max-turns)
          init-messages [system-message]]
      (when-let [has-prompt? (not (-> options' :prompt empty?))]
        (run-agent options' init-messages)
        (System/exit 0))
      (println "[Press ctrl-c to stop.]")
      (loop [messages init-messages]
        (recur (run-agent options' messages))))))
