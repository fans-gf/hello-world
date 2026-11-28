;;; Run "noob", the nano agent loop on chat completion endpoints.
;;; 
;;; Usage: clj -M -m noop [options]
;;; 
;;; Options:
;;;   -b, --base-url BASE_URL                       [ required ]
;;;   -e, --api-key-env API_KEY_ENV                 [ required ]
;;;   -m, --model MODEL                             [ required ]
;;;   -r, --reasoning-effort REASONING_EFFORT  max
;;;   -C, --confirm-tool-call                       [ Press Enter to confirm. ]
;;;   -S, --save-stop                               [ Press ctrl-d to save and stop. ]
;;;   -h, --help
;;;
;;; [ Press ctrl-d to save. ]
;;; [ Press ctrl-c to stop. ]

(ns noob
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter])
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

(declare chat cli-options input-save-stop messages! options parse-options
         run-agent run-bash-cmd run-bash-cmd-tool run-tool-call save! system-prompt)

(def cli-options
  [["-b" "--base-url BASE_URL" "[ required ]" :missing "Missing: --base-url"]
   ["-e" "--api-key-env API_KEY_ENV" "[ required ]" :missing "Missing: --api-key-env"]
   ["-m" "--model MODEL" "[ required ]" :missing "Missing: --model"]
   ["-r" "--reasoning-effort REASONING_EFFORT" :default "max"]
   ["-C" "--confirm-tool-call" "[ Press Enter to confirm. ]"]
   ["-S" "--save-stop" "[ Press ctrl-d to save and stop. ]"]
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

(defn save! [messages]
  (let [now (LocalDateTime/now)
        fmt (DateTimeFormatter/ofPattern "yyyyMMdd_HHmmss")
        now-str (.format now fmt)
        file-name (str "messages_" now-str ".txt")
        messages-str (json/generate-string messages)]
    (spit file-name messages-str)
    (println)
    (println "[" file-name "saved. ]")))

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

(defn input-save-stop [prompt-str & messages]
  (print prompt-str)
  (flush)
  (if-let [line (read-line)]
    line
    (do (if messages
          (do (save! messages)
              (when (:save-stop options)
                (System/exit 1)))
          (println "[ No new messages to save. ]"))
        (input-save-stop prompt-str))))

(defn run-bash-cmd [{:keys [cmd]}]
  (let [confirm-tool-call? (:confirm-tool-call options)]
    (when confirm-tool-call?
      (println "<<<")
      (println cmd)
      (input-save-stop ">>> [ Enter ] " messages!))
    (let [{:keys [exit out err]} (sh/sh "bash" "-lc" cmd)
          result (if (zero? exit)
                   (str out err)
                   (str "exit_code=" exit "\n" out err))]
      (when confirm-tool-call?
        (println result))
      result)))

(defn run-tool-call [{:keys [id]
                      {:keys [name arguments]} :function}
                     messages!]
  (def messages! messages!)
  {:role "tool"
   :tool_call_id id
   :content ((requiring-resolve (symbol "noob" name))
             (json/parse-string arguments true))})

(defn run-agent [messages]
  (loop [messages+user (let [user-prompt (input-save-stop "> " messages)
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
        (let [n (count tool_calls)
              messages+user+assistant+tools
              (loop [i 0
                     messages+user+assistant+tool messages+user+assistant]
                (if (= i n)
                  messages+user+assistant+tool
                  (let [tool-call (tool_calls i)
                        tool-message (run-tool-call tool-call messages+user+assistant+tool)]
                    (recur (inc i) (conj messages+user+assistant+tool tool-message)))))]
          (recur messages+user+assistant+tools))))))

(defn -main [& args]
  ((parse-options args)
   (println "[ Press ctrl-d to save. ]")
   (println "[ Press ctrl-c to stop. ]")
   (let [system-message {:role "system"
                         :content system-prompt}]
     (loop [messages [system-message]]
       (recur (run-agent messages))))))
