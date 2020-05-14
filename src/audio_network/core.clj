(ns audio-network.core
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.xml :as xml]
            [alandipert.enduro :as pers]
            [diehard.core :as d]
            [dk.ative.docjure.spreadsheet :as x]
            [etaoin.api :as e]
            [me.raynes.fs :as fs]
            [taoensso.timbre :as log])
  (:import (java.io File ByteArrayInputStream))
  (:gen-class))

;; --- Base dir utils

(defn join
  "Joins parts of a path correctly."
  [& args]
  (.getPath (apply io/file args)))


(def base-dir
  (join (fs/home) ".anw"))


(defn in-base-dir
  "Joins paths to base-dir"
  [& args]
  (apply join base-dir args))


(defn ensure-base-dir-exists!
  "Creates base dir if it doesn't exist already."
  []
  (when-not (fs/exists? base-dir)
    (fs/mkdir base-dir)))


;; --- Webdriver Management

(defonce headless? (atom true))
(defonce driver (atom nil))


(defn quit-driver-if-exists!
  "Quits current driver if it's open, resets atom."
  []
  (when @driver
    (e/quit @driver)
    (reset! driver nil)))


(defn reset-driver!
  "Shuts down current driver and instantiates a new one."
  []
  (quit-driver-if-exists!)
  (log/info "Resetting chrome driver.")
  (reset! driver (e/chrome {:headless @headless?})))


(defn ensure-driver-up!
  "Instantiates a driver if it doensn't exist already."
  []
  (when-not @driver
    (reset-driver!)))


;; --- Local db initialization

(defonce db (do
              (ensure-base-dir-exists!)
              (pers/file-atom {} (in-base-dir "db.atom"))))


;; --- Parsing

(def ^:const edl-block-marker #"(?m)(?=^\d{3})")            ; three digits mark a new EDl blocK
(def ^:const edl-block-re #"(\d{3})\s+(\w+)\s+(\w+)\s+(\w+)\s+(\d{2}:\d{2}:\d{2}:\d{2})\s+(\d{2}:\d{2}:\d{2}:\d{2})\s+(\d{2}:\d{2}:\d{2}:\d{2})\s+(\d{2}:\d{2}:\d{2}:\d{2})")


(defn parse-edl-block
  "Parses a block from an EDL file."
  [block]
  (let [[valid edit reel channel trans source-in source-out
         record-in record-out] (re-find edl-block-re block)
        mat     (re-matcher #"(?m)\* FROM CLIP NAME: (.*)[\r$]" block)
        matches (->> (repeatedly #(re-find mat))
                     (take-while some?)
                     (mapv second))
        from    (when-not (empty? matches)
                  (apply max-key count matches))]
    (when valid
      {:edit       edit
       :reel       reel
       :channel    channel
       :trans      trans
       :source-in  source-in
       :source-out source-out
       :record-in  record-in
       :record-out record-out
       :from       from})))


(defn parse-txt-line
  [line]
  (let [line-re #"([^\t]*)\t+([^\t]*)\t+([^\t]*)\t+([^\t]*)\t+([^\t]*)\t+([^\t]*)\t+([^\t]*)"
        ts-re   #"\d{2}:\d{2}:\d{2}:\d{2}"
        [_ _ _ from record-in record-out] (some->> line
                                                   (re-find line-re)
                                                   (mapv string/trim)
                                                   (remove empty?))
        _       (println (some->> line
                                  (re-find line-re)
                                  (mapv string/trim)))]
    (when (and from (re-find ts-re record-in))
      {:record-in  record-in
       :record-out record-out
       :from       from})))


(defmulti parse fs/extension)


(defmethod parse ".txt"
  [file]
  (->> (slurp file)
       (string/split-lines)
       (map parse-txt-line)
       (filter identity)))


(defmethod parse ".edl"
  [file]
  (let [[_ & blocks] (-> (slurp file)
                        (string/split edl-block-marker))]
    (->> blocks
         (map parse-edl-block)
         (filterv some?))))


;; --- Processing

(defn extract-timecode
  "Extracts hours, minutes, and seconds from a timecode string."
  [tc]
  (mapv #(Integer/parseInt %)
        (-> (re-find #"(\d{2}):(\d{2}):(\d{2})" tc)
            (subvec 1))))


(defn add-duration
  "Computes and adds duration to a parsed block."
  [{:keys [record-in record-out from]}]
  (let [time-in  (subs record-in 0 8)
        time-out (subs record-out 0 8)
        seconds  (let [[hi mi si] (extract-timecode time-in)
                       [ho mo so] (extract-timecode time-out)]
                   (+ (* 3600 (- ho hi))
                      (* 60 (- mo mi))
                      (- so si)))
        duration (format "%02d:%02d:%02d"
                         (quot seconds 3600)
                         (quot (mod seconds 3600) 60)
                         (mod seconds 60))]
    {:record-in  time-in
     :record-out time-out
     :duration   duration
     :from       from}))



(defn normalize-anw
  "Extracts the relevant part of a string containing an ANW code and normalizes it."
  [filename]
  (let [splitted (last (string/split filename #"(?=ANW)"))]
    (when (string/starts-with? splitted "ANW")
      (let [we (fs/base-name filename true)
            [_ m n] (re-find #"ANW\s?(\d{4}).(\d+)" we)]
        (when (and m n)
          (format "ANW%04d_%03d" (Integer/parseInt m) (Integer/parseInt n)))))))


(defn process
  "Given parsed blocks, processes them to produce all data necessary for a sheet.
  It filters, sorts, groups."
  [parsed-blocks]
  (->> parsed-blocks
       (map add-duration)
       (filter :from)
       (sort-by :record-in)
       (map (fn [{:keys [from] :as m}]
              (assoc m :from (string/trim
                               (if-let [no-suffix (second (re-find #"(.*)(_\d)$" from))]
                                 no-suffix
                                 from)))))
       (partition-by (fn [{:keys [from]}]
                       (if-let [n (normalize-anw from)]
                         n
                         from)))
       (fmap (fn [[entry :as items]]
               (let [{:keys [record-out]} (last items)]
                 (-> entry
                     (assoc :record-out record-out)
                     add-duration))))))


;; --- Scraping

(defn extract-inner-content
  "If given string is a HTML tag, returns its content."
  [s]
  (if (string/starts-with? s "<a")
    (-> s
        (.getBytes)
        (ByteArrayInputStream.)
        (xml/parse)
        :content
        first)
    s))


(defn scrape-anw-metadata!
  "Gets info for an Audio Network track"
  [driver query]
  (do
    (log/info "Searching" query "at Audio Network website.")

    (doto driver
      (e/go (str "https://www.audionetwork.com/track/searchkeyword?keyword=" query))
      (e/wait-visible {:css ".track__title"}))

    (let [[[title-el title]] (->> (e/query-all driver {:css ".track__title"})
                                  (mapv (fn [el]
                                          [el (e/get-element-attr-el driver el "title")]))
                                  (filterv (fn [[_ title]]
                                             (= (normalize-anw query) (normalize-anw title)))))
          without-awn (re-find #"\d+/\d+" title)]

      (log/info "Going to track page.")

      (doto driver
        (e/click-el title-el)
        (e/wait-visible {:css ".track__advanced-title"}))

      (into {"Written by" (e/get-element-inner-html-el driver
                                                       (e/query driver
                                                                {:css (format "div[data-trackanwref=\"%s\"] .track__artist" without-awn)}))
             "Track name" (e/get-element-attr-el driver
                                                 (e/query driver
                                                          {:css (format "div[data-trackanwref=\"%s\"]" without-awn)})
                                                 "data-tracktitle")}
            (map vector
                 (mapv (partial e/get-element-inner-html-el driver)
                       (e/query-all driver {:css ".track__advanced-title"}))
                 (mapv (comp extract-inner-content (partial e/get-element-inner-html-el driver))
                       (e/query-all driver {:css ".track__advanced-value"})))))))


(defn get-anw-metadata!
  "Gets ANW metadata for a given code. Searches on the website only if not on local db already."
  [anw-code]
  (let [data @db]
    (if (get-in data [anw-code "ISRC"])
      (do
        (log/info anw-code "found at local db.")
        (get data anw-code))
      (do
        (log/info anw-code "not found a local db.")

        (ensure-driver-up!)

        (try
          (let [data (d/with-retry {:retry-on    Exception
                                    :max-retries 3}
                                   (scrape-anw-metadata! @driver anw-code))]
            (log/info "Data fetched for" anw-code ":\n" data)
            (pers/swap! db assoc anw-code data)
            data)

          (catch Exception e nil))))))


;; --- End tools, working with specific files.

(defn cue-data-from-file
  "Returns all necessary cue sheet data from a file."
  [file]
  (let [processed (-> (if (string? file)
                        file
                        (.getPath ^File file))
                      parse
                      process)]

    (mapv (fn [item]
            (let [{:keys [record-in record-out duration from]} item
                  normalized (normalize-anw from)
                  db-entry   (if normalized
                               (get-anw-metadata! normalized)
                               {"Track name" ""
                                "Written by" ""
                                "ISRC"       ""})]

              {:from     (if normalized normalized from)
               :title    (db-entry "Track name")
               :duration duration
               :time-in  record-in
               :time-out record-out
               :authors  (db-entry "Written by")
               :isrc     (db-entry "ISRC")}))

          processed)))


(defn cue-from-file
  "Generates an excel sheet for a file."
  ([file template]
   (cue-from-file file template {}))

  ([file template {:keys [sheet start-row
                          title-col duration-col time-in-col time-out-col authors-col isrc-col interpreter-col]
                   :or   {sheet       1 start-row 12 title-col "A" duration-col "C"
                          time-in-col "D" time-out-col "E" authors-col "F" interpreter-col "J" isrc-col "L"}
                   :as   opts}]

   (let [data        (cue-data-from-file file)
         path        (if (string? file)
                       file
                       (.getPath ^File file))
         output-path (-> (string/split path #"\." 2)
                         first
                         (str ".xlsx"))
         wb          (x/load-workbook template)
         sheet       (if (int? sheet)
                       (nth (x/sheet-seq wb) (dec sheet))
                       (x/select-sheet wb sheet))]

     (doseq [[idx {:keys [from title duration time-in time-out authors isrc]}]
             (map-indexed (fn [idx item] [idx item]) data)]

       (let [row (+ start-row idx)]
         (x/set-cell! (x/select-cell (str title-col row) sheet)
                      (if (= title "")
                        from
                        (str from " - " title)))

         (x/set-cell! (x/select-cell (str duration-col row) sheet) duration)
         (x/set-cell! (x/select-cell (str time-in-col row) sheet) time-in)
         (x/set-cell! (x/select-cell (str time-out-col row) sheet) time-out)
         (x/set-cell! (x/select-cell (str authors-col row) sheet) authors)
         (x/set-cell! (x/select-cell (str interpreter-col row) sheet) authors)
         (x/set-cell! (x/select-cell (str duration-col row) sheet) duration)
         (x/set-cell! (x/select-cell (str isrc-col row) sheet) isrc)))

     (x/save-workbook! output-path wb))))


(defn cue-from-files
  "Generates cue sheets for a list of files."
  ([files template]
   (cue-from-files files template {}))

  ([files template opts]
   (doseq [file files]
     (let [path (if (string? file) file (.getPath ^File file))]
       (log/info "Working on" path "...")
       (cue-from-file path template opts)))))


(defn cue-from-dir
  "Generates cue sheets for all .edl or .txt files of a directory."
  ([dir template]
   (cue-from-dir dir template {}))

  ([dir template opts]
   (-> (fs/find-files* dir #(when (fs/file? %)
                              (contains? #{".edl" ".txt"} (string/lower-case (fs/extension %)))))
       (cue-from-files template opts))))


(comment
  (cue-from-dir "/home/lurodrigo/edl/pop" "/home/lurodrigo/edl/modelo_pop.xlsx" {}))