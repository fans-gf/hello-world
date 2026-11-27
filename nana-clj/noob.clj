(ns noob
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

(declare cli-options options parse-options)
(declare system-prompt run-bash-cmd-tool input)
(declare run-agent chat run-tool-call)

(def cli-options
  [["-b" "--base-url BASE_URL" :missing "Missing: --base-url"]
   ["-e" "--api-key-env API_KEY_ENV" :missing "Missing: --api-key-env"]
   ["-m" "--model MODEL" :missing "Missing: --model"]
   ["-r" "--reasoning-effort REASONING_EFFORT" :default "max"]
   ["-C" "--confirm-tool-call"]
   ["-h" "--help"]])

(defn parse-options [args]
  (let [{:keys [summary options errors]} (cli/parse-opts args cli-options)
        usage (str "Run \"noob\", the nano agent loop on chat completion endpoints.\n\n"
                   "Usage: clj -M -m noop [options]\n\nOptions:\n"
                   summary)]
    (when (:help options)
      (println usage)
      (System/exit 0))
    (when errors
      (binding [*out* *err*]
        (println (str/join "\n" errors))
        (println)
        (println usage)
        (System/exit 2)))
    (def options options)))

(def system-prompt
  (str/join "\n"
            ["You are bash specialist."
             "- Echo exactly the same back to simple greetings."
             "- Prefer to use `stdin stdout stderr` for non-complex tasks."
             "- Always use `/tmp` as default work space rather than the current directory."
             "- Don't download or install anything before consult with me for permission."
             "- Forbidden from `sudo`-ing anyway."
             "- Forbidden from `rm`-ing anything anyway."]))

(def run-bash-cmd-tool
  {:type "function"
   :function {:name "run-bash-cmd"
              :parameters {:type "object"
                           :properties {:cmd {:type "string"}}
                           :required ["cmd"]}}})

(defn input [prompt-str]
  (print prompt-str)
  (flush)
  (read-line))

(defn run-agent [messages]
  (loop [messages+user (let [user-prompt (input "> ")
                             user-message {:role "user"
                                           :content user-prompt}]
                         (conj messages user-message))]
    (let [assistant-message (-> messages+user chat :body :choices first :message)
          messages+user+assistant (conj messages+user assistant-message)
          {:keys [tool_calls content]} assistant-message]
      (if-let [no-tool-calls? (-> tool_calls seq not)]
        (do (println "< ")
            (println content)
            messages+user+assistant)
        (let [tools-messages (mapv run-tool-call tool_calls)
              messages+user+assistant+tools (into messages+user+assistant tools-messages)]
          (recur messages+user+assistant+tools))))))

(defn chat [messages]
  (let [{:keys [base-url api-key-env model reasoning-effort]} options
        url (str "https://" base-url "/v1/chat/completions")
        api-key (System/getenv api-key-env)
        auth-token (str "Bearer " api-key)]
    (http/post url
               {:headers {:authorization auth-token}
                :cookie-policy :none
                :content-type :json
                :decompress-body false
                :as :json
                :form-params {:model model
                              :reasoning_effort reasoning-effort
                              :tools [run-bash-cmd-tool]
                              :messages messages}})))

(defn run-tool-call [{:keys [id]
                      {:keys [name arguments]} :function}]
  (let [{:keys [cmd]} (json/parse-string arguments true)
        confirm-tool-call? (:confirm-tool-call options)]
    (when confirm-tool-call?
      (println "<<<")
      (println cmd)
      (input ">>> [Enter] "))
    (let [{:keys [out err]} (sh/sh "bash" "-lc" cmd)
          result (str out err)
          tool-message {:role "tool"
                        :tool_call_id id
                        :content result}]
      (when confirm-tool-call?
        (println result))
      tool-message)))

(defn -main [& args]
  ((parse-options args)
   (println "[Press ctrl-c to stop.]")
   (let [system-message {:role "system"
                         :content system-prompt}]
     (loop [messages [system-message]]
       (recur (run-agent messages))))))
