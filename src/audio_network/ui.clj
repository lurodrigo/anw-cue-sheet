(ns audio-network.ui
  (:require [cljfx.api :as fx]
            [alandipert.enduro :as pers]
            [me.raynes.fs :as fs]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [audio-network.core :as core]
            [clojure.string :as string])
  (:import [javafx.stage FileChooser DirectoryChooser]
           [javafx.event ActionEvent]
           [javafx.scene Node]
           [javafx.scene.control Alert Alert$AlertType]
           (java.io File))
  (:gen-class))

;; --- State

(defonce ui-info (do
                   (core/ensure-base-dir-exists!)
                   (pers/file-atom {:model-folder  nil
                                    :target-folder nil
                                    :last-model    nil}
                                   (core/in-base-dir "ui.atom"))))


(def state
  (atom {:title       "ANW Cue Sheet Generator"
         :model       (if-let [last-model (:last-model @ui-info)]
                        last-model "")
         :target      ""
         :target-type :file
         :headless?   true}))


(defn alert!
  "Helper for putting up an alert."
  ([msg]
   (alert! Alert$AlertType/NONE msg))
  ([type msg]
   (alert! type nil msg))
  ([type header msg]
   (let [alert (Alert. (case type
                         :alert/none Alert$AlertType/NONE
                         :alert/confirmation Alert$AlertType/CONFIRMATION
                         :alert/info Alert$AlertType/INFORMATION
                         :alert/warning Alert$AlertType/WARNING
                         :alert/error Alert$AlertType/ERROR))]
     (when header
       (.setHeaderText alert header))

     (doto alert
       (.setContentText msg)
       (.show)))))


;; --- Views

(defn model-input [{:keys [model]}]
  {:fx/type  :v-box
   :spacing  10
   :padding  5
   :children [{:fx/type :label
               :text    "Modelo de cue sheet"}
              {:fx/type  :h-box
               :spacing  10
               :children [{:fx/type    :text-field
                           :pref-width 650
                           :editable   false
                           :text       model}
                          {:fx/type   :button
                           :text      "Selecionar"
                           :on-action {:event/type ::select-model}}]}]})


(defn radio-group [{:keys [options value on-action]}]
  {:fx/type fx/ext-let-refs
   :refs    {::toggle-group {:fx/type :toggle-group}}
   :desc    {:fx/type  :h-box
             :spacing  10
             :children (for [[option-kw option-str] options]
                         {:fx/type      :radio-button
                          :toggle-group {:fx/type fx/ext-get-ref
                                         :ref     ::toggle-group}
                          :selected     (= option-kw value)
                          :text         option-str
                          :on-action    (assoc on-action :option option-kw)})}})

(defn many-files-rep
  [coll]
  (if (= (count coll) 1)
    (first coll)
    (->> coll
         (mapv (partial format "\"%s\""))
         (string/join " "))))

(defn target-input [{:keys [target]}]
  {:fx/type  :v-box
   :spacing  10
   :padding  5
   :children [{:fx/type  :h-box
               :spacing  10
               :children [{:fx/type :label
                           :text    "Alvo"}
                          {:fx/type   radio-group
                           :options   {:file "Arquivos" :folder "Pasta"}
                           :value     :file
                           :on-action {:event/type ::change-target-type}}
                          ]}

              {:fx/type  :h-box
               :spacing  10
               :children [{:fx/type    :text-field
                           :pref-width 650
                           :editable   false
                           :text       (if (string? target)
                                         target
                                         (many-files-rep target))}
                          {:fx/type   :button
                           :text      "Selecionar"
                           :on-action {:event/type ::select-target}}]}]})


(defn headless-checkbox [{:keys [headless?]}]
  {:fx/type  :v-box
   :spacing  10
   :padding  5
   :children [{:fx/type             :check-box
               :selected            headless?
               :on-selected-changed {:event/type ::toggle-headless}
               :text                "Ocultar Navegador"}]})


(defn root-view [{:keys [title model target headless?]}]
  {:fx/type :stage
   :showing true
   :title   title
   :on-close-request {:event/type ::close-app}
   :scene   {:fx/type :scene
             :root    {:fx/type     :v-box
                       :pref-width  800
                       :pref-height 450
                       :padding     10
                       :children    [{:fx/type model-input
                                      :model   model}
                                     {:fx/type target-input
                                      :target  target}
                                     {:fx/type   headless-checkbox
                                      :headless? headless?}
                                     {:fx/type   :button
                                      :text      "Gerar EDL!"
                                      :on-action {:event/type ::generate}}]}}})


;; --- Event handling

(defmulti handle :event/type)


(defmethod handle ::toggle-headless
  [_]
  (swap! state update :headless? not)
  (reset! core/headless? (:headless? @state)))


(defmethod handle ::close-app
  [_]
  (System/exit 0))


(defmethod handle ::change-target-type
  [{:keys [option] :as m}]
  (swap! state assoc :target-type option))


(defn get-if-file-exists
  "Gets an entry of a map if it points to a existing file."
  [m key]
  (when-let [d (get m key)]
    (when (fs/exists? d) d)))


(defmethod handle ::select-model [{:keys [^ActionEvent fx/event]}]
  (let [selection (let [window  (.getWindow (.getScene ^Node (.getTarget event)))
                        chooser (FileChooser.)]

                    (when-let [dir (get-if-file-exists @ui-info :model-folder)]
                      (.setInitialDirectory chooser (File. ^String dir)))

                    (.setTitle chooser "Escolha o modelo")
                    @(fx/on-fx-thread (.showOpenDialog chooser window)))]
    (when selection
      (swap! state assoc :model (.getPath ^File selection))
      (pers/swap! ui-info assoc :last-model (.getPath ^File selection))
      (pers/swap! ui-info assoc :model-folder (.getParent ^File selection)))))


(defmethod handle ::select-target [{:keys [^ActionEvent fx/event]}]
  (let [init-dir  (get-if-file-exists @ui-info :target-folder)
        selection (case (:target-type @state)
                    :file
                    (let [window  (.getWindow (.getScene ^Node (.getTarget event)))
                          chooser (doto (FileChooser.)
                                    (.setTitle "Escolha os arquivos:"))]

                      (when init-dir
                        (.setInitialDirectory chooser (File. ^String init-dir)))

                      (seq @(fx/on-fx-thread (.showOpenMultipleDialog chooser window))))

                    :folder
                    (let [window  (.getWindow (.getScene ^Node (.getTarget event)))
                          chooser (doto (DirectoryChooser.)
                                    (.setTitle "Escolha a pasta:"))]

                      (when init-dir
                        (.setInitialDirectory chooser (File. ^String init-dir)))

                      @(fx/on-fx-thread (.showDialog chooser window))))]

    (when selection
      (if (coll? selection)
        (let [first-selected (first selection)]
          (swap! state assoc :target (mapv #(.getPath ^File %) selection))
          (pers/swap! ui-info assoc :target-folder (.getParent ^File first-selected)))
        (do
          (swap! state assoc :target (.getPath ^File selection))
          (pers/swap! ui-info assoc :target-folder (.getParent ^File selection)))))))


(defn generate-files!
  []
  (let [{:keys [model target]} @state]
    (core/reset-driver!)

    (try
      (do
        (if (coll? target)
          (core/cue-from-files target model {})
          (core/cue-from-dir target model {}))

        (core/quit-driver-if-exists!)
        (alert! :alert/info "Uhull!" "EDL(s) gerados com sucesso!"))

      (catch Exception e
        (alert! :alert/error "Ocorreu um erro!" (str e))))))


(defmethod handle ::generate
  [_]
  (let [{:keys [model target]} @state
        non-existing (when (coll? target)
                       (vec (remove fs/exists? target)))]
    (cond
      (empty? model)
      (alert! :alert/error "Ops..." "Você precisa escolher um modelo!")

      (not (fs/exists? model))
      (alert! :alert/error "Ops..." "O modelo escolhido não existe mais!")

      (empty? target)
      (alert! :alert/error "Ops..." "Você precisa escolher um alvo!")

      (not (empty? non-existing))
      (alert! :alert/error "Ops..."
              (cond
                (= (count target) 1)
                "O alvo escolhido não existe mais!"

                (= (count non-existing) 1)
                (format "O alvo \"%s\" não existe mais!" (first non-existing))

                :else
                (format "Os alvos\n%s\n não existem mais!" (string/join "\n" non-existing))))

      (and (string? target) (fs/exists? target))
      (alert! :alert/error "Ops..." "O alvo escolhido não existe mais!")

      :else
      (generate-files!))))


;; --- Initialization

(def renderer
  (fx/create-renderer
    :middleware (fx/wrap-map-desc #(root-view %))
    :opts {:fx.opt/map-event-handler handle}))


(defn mount-renderer!
  []
  (fx/mount-renderer state renderer))


(defn -main
  []
  (timbre/merge-config!
    {:appenders {:spit (appenders/spit-appender {:fname (core/in-base-dir "anw.log")})}})
  (mount-renderer!))