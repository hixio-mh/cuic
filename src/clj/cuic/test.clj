(ns cuic.test
  (:require [clojure.test :as t]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]
            [clojure.tools.logging :refer [debug]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cuic.core :refer [wait] :as core]
            [cuic.impl.retry :as retry])
  (:import (java.io File)
           (java.awt.image BufferedImage)
           (javax.imageio ImageIO)
           (com.github.kilianB.hashAlgorithms PerceptiveHash)
           (cuic WaitTimeoutException)))

(defn- images-match? [^BufferedImage expected ^BufferedImage actual]
  (let [ph (PerceptiveHash. (:hash-bits (:image-match core/*config*)))
        d  (.hammingDistance (.hash ph expected) (.hash ph actual))]
    (debug "Got image distance" d)
    (< d (:threshold (:image-match core/*config*)))))

(defn- snapshots-dir ^File []
  (io/file (:snapshot-dir core/*config*)))

(defn- snapshot-filename [id]
  (let [fq-str (if (namespace id)
                 (str (namespace id) "___" (name id))
                 (name id))]
    (-> (string/replace fq-str #"-" "_")
        (string/replace #"[^\w]+" ""))))

(defn expected-file [id ext]
  (io/file (snapshots-dir) (str (snapshot-filename id) ".expected." ext)))

(defn actual-file [id ext]
  (io/file (snapshots-dir) (str (snapshot-filename id) ".actual." ext)))

(defn read-edn [^File f]
  (if (.exists f)
    (edn/read-string (slurp f))))

(defn read-image [^File f]
  (if (.exists f)
    (ImageIO/read f)))

(defn- ensure-snapshot-dir! []
  (let [dir (snapshots-dir)]
    (when-not (.exists dir)
      (.mkdirs dir)
      (spit (io/file dir ".gitignore") "*.actual*\n"))
    dir))

(defn- test-snapshot [id actual predicate read write! ext]
  (ensure-snapshot-dir!)
  (let [e-file   (expected-file id ext)
        a-file   (actual-file id ext)
        expected (read e-file)]
    (if (.exists e-file)
      (if-not (predicate expected actual)
        (do (write! (io/file a-file) actual)
            false)
        (do (if (.exists a-file) (.delete a-file))
            true))
      (do (write! e-file actual)
          (println " > Snapshot written : " (.getAbsolutePath e-file))
          true))))

(defmacro -with-retry [f timeout expr-form]
  `(let [report# (atom nil)
         result# (with-redefs [t/do-report #(reset! report# %)]
                   (try
                     (retry/loop* ~f ~timeout ~expr-form)
                     (catch WaitTimeoutException e#
                       (if-some [cause# (.getCause e#)]
                         (throw cause#)
                         (:actual (ex-data e#))))))]
     (some-> @report# (t/do-report))
     result#))

(defmacro is
  "Assertion macro that works like clojure.test/is but if the result value is
   non-truthy or asserted expression throws a cuic exception, then expression
   is re-tried until truthy value is received or timeout exceeds."
  [form]
  `(t/is (-with-retry #(do ~(t/assert-expr nil form)) (:timeout core/*config*) '~form)))

(defn matches-snapshot?
  [snapshot-id actual]
  {:pre [(keyword? snapshot-id)]}
  (let [predicate #(= %1 %2)
        read      read-edn
        write!    #(spit (io/file %1) (with-out-str (pprint %2)))]
    (test-snapshot snapshot-id actual predicate read write! "edn")))

(defn matches-screenshot?
  [screenshot-id actual]
  {:pre [(keyword? screenshot-id)
         (instance? BufferedImage actual)]}
  (let [predicate images-match?
        read      read-image
        write!    #(ImageIO/write %2 "PNG" %1)]
    (test-snapshot screenshot-id actual predicate read write! "png")))

(defn -assert-snapshot [msg [match? id actual]]
  `(let [id#       (do ~id)
         actual#   (do ~actual)
         expected# (read-edn (expected-file id# "edn"))
         result#   (~match? id# actual#)]
     (if result#
       (t/do-report
         {:type     :pass
          :message  ~msg
          :expected '(~'matches-snapshot? ~id ~actual)
          :actual   (cons '~'= [expected# actual#])})
       (t/do-report
         {:type     :fail
          :message  ~msg
          :expected '(~'matches-snapshot? ~id ~actual)
          :actual   (list '~'not (cons '~'= [expected# actual#]))}))
     result#))

(defn -assert-with-retry [msg form]
  `(try
     ~form
     (catch Throwable t#
       (t/do-report
         {:type     :error
          :message  ~msg
          :expected ~(last form)
          :actual   t#}))))

(defmethod t/assert-expr 'matches-snapshot? [msg form]
  (-assert-snapshot msg form))

(defmethod t/assert-expr `matches-snapshot? [msg form]
  (-assert-snapshot msg form))

(defmethod t/assert-expr '-with-retry [msg form]
  (-assert-with-retry msg form))

(defmethod t/assert-expr `-with-retry [msg form]
  (-assert-with-retry msg form))
