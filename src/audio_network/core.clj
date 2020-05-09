(ns audio-network.core
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as string]
            [clojure.xml :as xml]
            [alandipert.enduro :as pers]
            [diehard.core :as d]
            [dk.ative.docjure.spreadsheet :as x]
            [etaoin.api :as e]
            [me.raynes.fs :as fs]
            [taoensso.timbre :as log])
  (:import (java.io File ByteArrayInputStream)))

(defonce db (do
              (fs/mkdir (fs/expand-home "~/.anw"))
              (pers/file-atom {} (fs/expand-home "~/.anw/db.atom"))))

(defonce headless? (atom true))

(defonce driver (atom nil))

(def ^:const line-marker #"(?m)(?=^\d{3})")
(def ^:const line-re #"(\d{3})\s+(\w+)\s+(\w+)\s+(\w+)\s+(\d{2}:\d{2}:\d{2}:\d{2})\s+(\d{2}:\d{2}:\d{2}:\d{2})\s+(\d{2}:\d{2}:\d{2}:\d{2})\s+(\d{2}:\d{2}:\d{2}:\d{2})")
(def ^:const anw-re #"(ANW[\w_]*);")
(def ^:const isrc-re #"ISRC:([\w-]+);")

(defn without-ext
  [filename]
  (first (string/split filename #"\.")))

(defn normalize-anw
  [filename]
  (let [splitted (last (string/split filename #"(?=ANW)"))]
    (when (string/starts-with? splitted "ANW")
      (let [we (without-ext filename)
            [_ m n] (re-find #"ANW\s?(\d{4}).(\d+)" we)]
        (when (and m n)
          (format "ANW%04d_%03d" (Integer/parseInt m) (Integer/parseInt n)))))))

(defn parse-line
  [line]
  (let [[valid edit reel channel trans source-in source-out
         record-in record-out] (re-find line-re line)
        mat     (re-matcher #"(?m)\* FROM CLIP NAME: (.*)[\r$]" line)
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

(defn extract-tc
  [tc]
  (mapv #(Integer/parseInt %)
        (-> (re-find #"(\d{2}):(\d{2}):(\d{2})" tc)
            (subvec 1))))

(defn process-entry
  [{:keys [record-in record-out from]}]
  (let [time-in  (subs record-in 0 8)
        time-out (subs record-out 0 8)
        seconds  (let [[hi mi si] (extract-tc time-in)
                       [ho mo so] (extract-tc time-out)]
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

(defn parse-txt-line
  [line]
  (let [line-re #"([^\t]*)\t+([^\t]*)\t+([^\t]*)\t+([^\t]*)\t+([^\t]*)\t+([^\t]*)\t+([^\t]*)"
        ts-re   #"\d{2}:\d{2}:\d{2}:\d{2}"
        [_ _ _ from record-in record-out] (some->> line
                                                   (re-find line-re)
                                                   (mapv string/trim)
                                                   (remove empty?))
        _ (println (some->> line
                            (re-find line-re)
                            (mapv string/trim)))]
    (when (and from (re-find ts-re record-in))
      {:record-in  record-in
       :record-out record-out
       :from       from})))

(defmulti parse fs/extension)

(defmethod parse ".txt"
  [file]
  {:meta         ""
   :parsed-lines (->> (slurp file)
                      (string/split-lines)
                      (map parse-txt-line)
                      (filter identity))})

(defmethod parse ".edl"
  [file]
  (let [[meta & lines] (-> (slurp file)
                           (string/split line-marker))
        parsed-lines (mapv parse-line lines)]
    {:meta         meta
     :parsed-lines (filterv some? parsed-lines)}))

(defn process
  [{:keys [parsed-lines]}]
  (->> parsed-lines
       (map process-entry)
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
                     process-entry))))))

(defn music-file?
  [extensions file]
  (let [exts (set (map string/lower-case extensions))]
    (some->> (fs/extension file)
             (string/lower-case)
             (contains? exts))))

(defn clean-metadata
  [s]
  (let [tag-map  (->> (string/split-lines s)
                      (filter #(re-find #".*:.*" %))
                      (mapv (fn [line]
                              (let [[k & vs] (string/split line #":")
                                    v (string/join ":" vs)]
                                (mapv string/trim [k v]))))
                      (into {}))
        com      (tag-map "Comment")
        anw-code (when com
                   (second (re-find anw-re com)))
        isrc     (when com
                   (second (re-find isrc-re com)))]
    (merge tag-map (merge tag-map {"ANW Code" anw-code
                                   "ISRC"     isrc}))))

(defn metadata-from-dirs
  [dirs extensions]
  (->> (reverse dirs)
       (mapcat #(fs/find-files* % (partial music-file? extensions)))
       (mapv (fn [file]
               (let [{:keys [exit out]} (sh "mediainfo" (.getPath ^File file))]
                 (when (zero? exit)
                   [(some-> (fs/base-name file)
                            normalize-anw
                            string/upper-case)
                    (clean-metadata out)]))))
       (into {})))

(defn metadata-from-dirs!
  [dirs extensions]
  (pers/swap! db (fn [v]
                   (merge (metadata-from-dirs dirs extensions) v))))

(defn extract-inner
  [s]
  (if (string/starts-with? s "<a")
    (-> s
        (.getBytes)
        (ByteArrayInputStream.)
        (xml/parse)
        :content
        first)
    s))

(defn an-info
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
                 (mapv (comp extract-inner (partial e/get-element-inner-html-el driver))
                       (e/query-all driver {:css ".track__advanced-value"})))))))

; TODO ensure exists properly, dispose
(defn reset-driver!
  []
  (when-not @driver
    (log/info "Resetting chrome driver.")
    (reset! driver (e/chrome {:headless @headless?}))))

(defn get-an-info!
  [anw-code]
  (let [data @db]
    (if (get-in data [anw-code "ISRC"])
      (do
        (log/info anw-code "found at local db.")
        (get data anw-code))
      (do
        (log/info anw-code "not found a local db.")

        (reset-driver!)

        (try
          (let [data (d/with-retry {:retry-on    Exception
                                    :max-retries 3}
                                   (an-info @driver anw-code))]
            (log/info "Data fetched for" anw-code ":\n" data)
            (pers/swap! db assoc anw-code data)
            data)

          (catch Exception e nil))))))

(defonce at (atom nil))

(defn cue-data-from-file [file]
  (let [processed (-> (if (string? file)
                        file
                        (.getPath file))
                      parse
                      process)]
    (mapv (fn [item]
            (let [{:keys [record-in record-out duration from]} item
                  normalized (normalize-anw from)
                  db-entry   (if normalized
                               (get-an-info! normalized)
                               {"Track name" ""
                                "Written by" ""
                                "ISRC"       ""})]

              (reset! at db-entry)
              {:from     (if normalized normalized from)
               :title    (db-entry "Track name")
               :duration duration
               :time-in  record-in
               :time-out record-out
               :authors  (db-entry "Written by")
               :isrc     (db-entry "ISRC")}))
          processed)))

(defn cue-from-file
  [file template {:keys [sheet start-row
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

    (x/save-workbook! output-path wb)))

(defn cue-from-files
  [dir template opts]
  (doseq [edl (fs/find-files* dir #(when (fs/file? %)
                                     (contains? #{".edl" ".txt"} (string/lower-case (fs/extension %)))))]
    (println "Working on" (.getPath ^File edl) "...")
    (cue-from-file edl template opts)))

(defn -main [& args])

(comment
  (cue-from-files "/home/lurodrigo/edl/pop" "/home/lurodrigo/edl/modelo_pop.xlsx" {}))