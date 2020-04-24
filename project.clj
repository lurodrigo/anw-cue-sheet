(defproject audio-network "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :repositories {"jtagger" "https://dl.bintray.com/ijabz/maven"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/algo.generic "0.1.3"]
                 [alandipert/enduro "1.2.0"]
                 [com.fzakaria/slf4j-timbre "0.3.14"]
                 [com.taoensso/timbre "4.10.0"]
                 [diehard "0.9.1"]
                 [dk.ative/docjure "1.13.0"]
                 [etaoin "0.3.6"]
                 [me.raynes/fs "1.4.6"]
                 [seesaw "1.5.0"]]
  :repl-options {:init-ns audio-network.core})
