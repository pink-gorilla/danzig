(ns hanse.danzig.io
  (:require
   #?(:clj [clojure.java.io :as io])
   #?(:clj [clojure.data.csv :as csv])
   [net.cgrand.xforms :as x]
   #?(:clj [net.cgrand.xforms.io :as xio])
   #?(:clj [java-time :as jt])
   [hanse.danzig :refer [vecs->maps comp-some]]
   #?(:cljs ["fs" :as fs])
   [clojure.string :as str])
  (:import
   #?(:clj (clojure.lang Keyword Fn))))

(def ^:private dtype->fn {:long     #?(:clj  #(Long/parseLong ^String %)
                                       :cljs #(js/parseInt ^String %))
                          :double   #?(:clj  #(Double/parseDouble ^String %)
                                       :cljs #(js/parseFloat ^String %))
                          :date     #?(:clj  #(jt/local-date ^String %)
                                       :cljs nil)
                          :datetime #?(:clj  #(jt/local-date-time ^String %)
                                       :cljs nil)
                          nil       identity})

(defmulti ^:private parse-dtype (fn [val] (class val)))
(defmethod ^:private parse-dtype Keyword [key] (dtype->fn key))
(defmethod ^:private parse-dtype Fn [f] f)

(defmulti ^:private add-header (fn [x & _] (class x)))

(defmethod ^:private add-header #?(:clj java.lang.Long :cljs js/Number)
  [i & [keywordize-headers?]]
  (comp
   (x/transjuxt {:xs (comp (drop (inc i)) (x/into []))
                 :headers (comp-some
                           (when (<= 1 i) (drop i))
                           (take 1)
                           (when keywordize-headers?
                             (map #(map keyword %)))
                           (map #(map vector % (range))))})
   (mapcat (fn [{:keys [xs headers]}]
             (into [] (vecs->maps (into {} headers)) xs)))))

(defmethod ^:private add-header #?(:clj java.lang.Long :cljs js/Number)
  [i & [keywordize-headers?]]
  (fn [rf]
    (let [n_      (volatile! 0)
          headers_ (volatile! ::none)]
      (fn
        ([] (rf))
        ([acc] (rf acc))
        ([acc x]
         (cond
           (and (identical? @headers_ ::none) (< @n_ i))
           (do
             (vswap! n_ inc)
             acc)

           (identical? @n_ i)
           (let [ks (cond->> x
                      keywordize-headers?
                      (mapv #(-> % (str/trim) (str/lower-case) (str/replace " " "-") (str/replace "\"" "") keyword)))]
             (vswap! n_ inc)
             (vreset! headers_ ks)
             acc)

           :else
           (rf acc (into {} (map vector @headers_ x)))))))))

(defmethod ^:private add-header clojure.lang.IPersistentMap
  [m & _]
  (vecs->maps m))

(defn remove-quote [q]
  (fn [rf]
    (fn
      ([] (rf))
      ([acc] (rf acc))
      ([acc x]
       (let [x (reduce-kv
                (fn [acc k v]
                  (assoc acc k (str/trim (str/replace v (re-pattern q) ""))))
                {}
                x)]
         (rf acc x))))))

(defn read-csv
  ([path {:keys [sep quote drop-lines header encoding keywordize-headers? parse]
          :or {sep                 ","
               drop-lines          nil
               encoding            "utf-8"
               keywordize-headers? false}}]
   (->> (xio/lines-in path :encoding encoding)
        (into []
              (comp-some
               (when drop-lines (drop drop-lines))
               (map #(clojure.string/split % (re-pattern sep)))
               (when header (add-header header keywordize-headers?))
               (when quote (remove-quote quote))
               (when parse
                 (map (fn [m]
                        (reduce-kv (fn [acc k v]
                                     (update acc k (parse-dtype v)))
                                   m parse))))))))
  ([path]
   (read-csv path {})))

(defn to-csv
  ([path data {:keys [sep add-headers? add-index? encoding format]
               :or   {sep          ","
                      add-headers? true
                      add-index?   true
                      encoding     "utf8"}}]
   (xio/lines-out
    path
    (comp-some
     (when add-index?
       (map-indexed #(merge {:_index %1} %2)))
     (when add-headers?
       (comp
        (x/transjuxt {:xs           (comp (map vals) (x/into []))
                      :headers (comp (take 1) (map keys))})
        (mapcat (fn [{:keys [xs headers]}]
                  (into [headers] xs)))))
     (when format
       (map (fn [m]
              (reduce-kv (fn [acc k f]
                           (update acc k f))
                         m format))))
     (map #(clojure.string/join sep %)))
    data
    :encoding encoding))
  ([path data]
   (to-csv path data {})))
