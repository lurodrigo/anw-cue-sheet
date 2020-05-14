(ns audio-network.old)

; [clojure.java.shell :refer [sh]]

;(defn music-file?
;  [extensions file]
;  (let [exts (set (map string/lower-case extensions))]
;    (some->> (fs/extension file)
;             (string/lower-case)
;             (contains? exts))))
; (def ^:const anw-re #"(ANW[\w_]*);")
; (def ^:const isrc-re #"ISRC:([\w-]+);")
;
;(defn clean-metadata
;  [s]
;  (let [tag-map  (->> (string/split-lines s)
;                      (filter #(re-find #".*:.*" %))
;                      (mapv (fn [line]
;                              (let [[k & vs] (string/split line #":")
;                                    v (string/join ":" vs)]
;                                (mapv string/trim [k v]))))
;                      (into {}))
;        com      (tag-map "Comment")
;        anw-code (when com
;                   (second (re-find anw-re com)))
;        isrc     (when com
;                   (second (re-find isrc-re com)))]
;    (merge tag-map (merge tag-map {"ANW Code" anw-code
;                                   "ISRC"     isrc}))))
;
;
;(defn metadata-from-dirs
;  [dirs extensions]
;  (->> (reverse dirs)
;       (mapcat #(fs/find-files* % (partial music-file? extensions)))
;       (mapv (fn [file]
;               (let [{:keys [exit out]} (sh "mediainfo" (.getPath ^File file))]
;                 (when (zero? exit)
;                   [(some-> (fs/base-name file)
;                            normalize-anw
;                            string/upper-case)
;                    (clean-metadata out)]))))
;       (into {})))
;
;
;(defn metadata-from-dirs!
;  [dirs extensions]
;  (pers/swap! db (fn [v]
;                   (merge (metadata-from-dirs dirs extensions) v))))