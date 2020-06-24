(defproject audio-network "0.1.4"
  :description "Tool for automatic generation of cue sheets with data from Audio Network"
  :url "http://github.com/lurodrigo/anw-tool"
  :license {:name "MIT License"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/algo.generic "0.1.3"]
                 [alandipert/enduro "1.2.0"]
                 [cljfx "1.7.2"]
                 [com.fzakaria/slf4j-timbre "0.3.14"]
                 [com.taoensso/timbre "4.10.0"]
                 [diehard "0.9.1"]
                 [dk.ative/docjure "1.13.0"]
                 [etaoin "0.3.6"]
                 [me.raynes/fs "1.4.6"]]
  :main audio-network.ui
  :profiles {:uberjar {:aot        :all
                       :injections [(javafx.application.Platform/exit)]}}
  :repl-options {:init-ns audio-network.core})
