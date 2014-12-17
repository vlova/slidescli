(ns slidescli.core
  (:require
   [clj-http.client :as http]
   [hickory.core :as hc]
   [hickory.select :as hs]
   [clojure.string :as str]
   [clojure.data.json :as json])
  (:import [java.io Console])
  (:gen-class :main true))

(def ^:const login-url "https://slides.com/users/sign_in")

(def deck-state (ref {}))
(def deck-info (ref {}))

(defn- get-hickory-page [url & [parameters]]
  (->>
   (http/get url (merge {:insecure? true} parameters))
   (:body)
   (hc/parse)
   (hc/as-hickory)))

(defn- get-authenticity-token []
  (->>
   (get-hickory-page login-url)
   (hs/select
    (hs/attr :name (partial = "authenticity_token")))
   (first)
   (:attrs)
   (:value)))

(defn- post-auth-data [token email password]
  (http/post login-url
             {:form-params {"utf-8" "✓"
                            "authenticity_token" token
                            "user[email]" email
                            "user[password]" password
                            "user[remember_me]" 0}
              :insecure? true}))

(defn read-password []
  (-> (.. System console (readPassword))
      (String.)))

(defn- authenticate! [email password]
  (let [token (get-authenticity-token)]
    (let [result (post-auth-data token email password)
          status (:status result)]
      (if (= status 301)
        (throw (Exception. "bad email or password")))))
  nil)

(defn get-deck-speakerView-url [deck-name]
  (str "http://slides.com/viktorlova/" deck-name "/speaker"))

(defn get-speakerView-CSRF-token [deck-name]
  (->>
   (get-hickory-page (get-deck-speakerView-url deck-name))
   (hs/select
    (hs/attr :name (partial = "csrf-token")))
   (first)
   (:attrs)
   (:content)))

(defn get-deck-info [deck-name]
  (->>
   deck-name
   (get-deck-speakerView-url)
   (get-hickory-page)
   (hs/select
    (hs/and
     (hs/tag :script)))
   (map :content)
   (filter (comp not nil?))
   (map first)
   (map (partial re-seq #"var SLConfig = \{(.*)\}"))
   (filter (comp not nil?))
   (map ffirst)
   (map #(str/replace % #"var SLConfig = (?<json>\{.*\})" "${json}"))
   (first)
   (json/read-json)
   ))

(defn get-deck-id [deck-name]
  (as->
   (get-deck-info deck-name) $
   (get-in $ [:deck :id])))

(defn get-stream-url [& [deck-id]]
  (str "http://slides.com/api/v1/decks/" (if (nil? deck-id) (:deck-id @deck-info) deck-id) "/stream.json"))

(defn update-from-server-info! [server-info]
  (let [server-info (json/read-json (:body server-info))]
    (dosync
     (alter deck-state merge
            (->> (:state server-info)
                 json/read-json)))))

(defn set-deck! [deck-name]
  (let [token (get-speakerView-CSRF-token deck-name)
        deck-id (get-deck-id deck-name)
        server-info (http/get (get-stream-url deck-id)
                              {:insecure? true
                               :headers {"X-CSRF-TOKEN" token
                                         "Referer" "http://slides.com/viktorlova/instantfeedback/speaker"
                                         "X-Requested-With" "XMLHttpRequest"}})]
    (dosync
     (alter deck-info merge
            {:deck-name deck-name
             :deck-id deck-id
             :token token})
     (update-from-server-info! server-info))))

(defn update-local-state! [state]
  (dosync
   (alter deck-state merge state)
   @deck-state))

(defn send-state! [state]
  (let [server-info
        (http/put (get-stream-url)
                  {:form-params {:state (json/write-str state)}
                   :headers {"X-CSRF-TOKEN" (:token deck-info)
                             "Referer" "http://slides.com/viktorlova/instantfeedback/speaker"
                             "X-Requested-With" "XMLHttpRequest"}
                   :insecure? true})]
    (update-from-server-info! server-info)))

(defn change-index! [name fn]
  (dosync
   (alter deck-state merge
          {name (fn (name @deck-state))}))
  (send-state! @deck-state))

(defn as-integer [command prefix]
  (-> (.. command
          (substring (count prefix))
          (trim))
      (Integer/valueOf)))

(defn console-loop []
  (let [command (-> (read-line) (str/lower-case))
        short-form (-> command (first) (str))]
    (condp = short-form
      "n" (change-index! :indexh inc)
      "p" (change-index! :indexh dec)
      "u" (change-index! :indexv inc)
      "d" (change-index! :indexv dec)
      "v" (change-index! :indexv (constantly (as-integer command "v")))
      "h" (change-index! :indexh (constantly (as-integer command "h"))))
    (if (= command "q")
      nil
      (recur))))

(defn -main [& args]
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]

    (let [email (do (println "Email: ") (read-line))
          passw (do (println "Password: ") (read-password))]
      (authenticate! email passw))

    (println "Deck name: ")
    (let [deck-name (read-line)]
      (set-deck! deck-name))

    (console-loop)))
