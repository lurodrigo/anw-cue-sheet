(ns audio-network.ui
  (:require [cljfx.api :as fx]
            [alandipert.enduro :as pers]
            [me.raynes.fs :as fs]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [audio-network.core :as core])
  (:import [javafx.stage FileChooser DirectoryChooser]
           [javafx.event ActionEvent]
           [javafx.scene Node]
           [javafx.scene.control Alert Alert$AlertType]
           (java.io File))
  (:gen-class))

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

;; Define render functions

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


(defn target-input [{:keys [target target-type]}]
  {:fx/type  :v-box
   :spacing  10
   :padding  5
   :children [{:fx/type  :h-box
               :spacing  10
               :children [{:fx/type :label
                           :text    "Alvo"}
                          {:fx/type   radio-group
                           :options   {:file "Arquivo" :folder "Pasta"}
                           :value     :file
                           :on-action {:event/type ::change-target-type}}
                          ]}

              {:fx/type  :h-box
               :spacing  10
               :children [{:fx/type         :text-field
                           :pref-width      650
                           :editable        false
                           :text            target}
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


; event handling

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


(defmethod handle ::select-model [{:keys [^ActionEvent fx/event]}]
  (let [selection (let [window  (.getWindow (.getScene ^Node (.getTarget event)))
                        chooser (FileChooser.)]

                    (when-let [dir (:model-folder @ui-info)]
                      (.setInitialDirectory chooser (File. ^String dir)))

                    (.setTitle chooser "Escolha o modelo")
                    @(fx/on-fx-thread (.showOpenDialog chooser window)))]
    (when selection
      (swap! state assoc :model (.getPath ^File selection))
      (pers/swap! ui-info assoc :last-model (.getPath ^File selection))
      (pers/swap! ui-info assoc :model-folder (.getParent ^File selection)))))


(defmethod handle ::select-target [{:keys [^ActionEvent fx/event]}]
  (let [init-dir  (:target-folder @ui-info)
        selection (case (:target-type @state)
                    :file
                    (let [window  (.getWindow (.getScene ^Node (.getTarget event)))
                          chooser (doto (FileChooser.)
                                    (.setTitle "Escolha o arquivo"))]

                      (when init-dir
                        (.setInitialDirectory chooser (File. ^String init-dir)))

                      @(fx/on-fx-thread (.showOpenDialog chooser window)))

                    :folder
                    (let [window  (.getWindow (.getScene ^Node (.getTarget event)))
                          chooser (doto (DirectoryChooser.)
                                    (.setTitle "Escolha a pasta"))]

                      (when init-dir
                        (.setInitialDirectory chooser (File. ^String init-dir)))

                      @(fx/on-fx-thread (.showDialog chooser window))))]
    (when selection
      (swap! state assoc :target (.getPath ^File selection))
      (pers/swap! ui-info assoc :target-folder (.getParent ^File selection)))))


(defn generate-files!
  []
  (let [{:keys [model target]} @state]
    (core/reset-driver!)

    (try
      (do
        (if (fs/directory? target)
          (core/cue-from-dir target model {})
          (core/cue-from-file target model {}))

        (core/quit-driver-if-exists!)
        (alert! :alert/info "Uhull!" "EDL(s) gerados com sucesso!"))

      (catch Exception e
        (alert! :alert/error "Ocorreu um erro!" (str e))))))


(defmethod handle ::generate
  [_]
  (let [{:keys [model target]} @state]
    (cond
      (empty? model)
      (alert! :alert/error "Ops..." "Você precisa escolher um modelo!")

      (not (fs/exists? model))
      (alert! :alert/error "Ops..." "O modelo escolhido não existe mais!")

      (empty? target)
      (alert! :alert/error "Ops..." "Você precisa escolher um alvo!")

      (not (fs/exists? target))
      (alert! :alert/error "Ops..." "O alvo escolhido não existe mais!")

      :else
      (generate-files!))))


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