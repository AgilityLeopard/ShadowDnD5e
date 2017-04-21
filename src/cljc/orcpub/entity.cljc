(ns orcpub.entity
  (:require [clojure.spec :as spec]
            [orcpub.common :as common]
            [orcpub.modifiers :as mods]
            [orcpub.entity-spec :as es]
            [orcpub.template :as t]
            [clojure.set :refer [difference union intersection]]
            #?(:cljs [cljs.pprint :as pp])))

(spec/def ::key keyword?)
(spec/def ::option (spec/keys :req [::key]
                              :opt [::options]))
(spec/def ::option-vec (spec/* ::option))
(spec/def ::options (spec/map-of keyword? (spec/or :single ::option
                                                   :multiple ::option-vec)))
(spec/def ::raw-entity (spec/keys :opt [::options]))

(spec/def ::flat-option (spec/keys :req [::t/path]
                                   :opt [::value]))
(spec/def ::flat-options (spec/+ ::flat-option))


;;============== topo sort ===============

(defn without
  "Returns set s with x removed."
  [s x] (difference s #{x}))

(defn take-1
  "Returns the pair [element, s'] where s' is set s with element removed."
  [s] {:pre [(not (empty? s))]}
  (let [item (first s)]
    [item (without s item)]))

(defn no-incoming
  "Returns the set of nodes in graph g for which there are no incoming
  edges, where g is a map of nodes to sets of nodes."
  [g]
  (let [nodes (set (keys g))
        have-incoming (apply union (vals g))]
    (difference nodes have-incoming)))

(defn normalize
  "Returns g with empty outgoing edges added for nodes with incoming
  edges only.  Example: {:a #{:b}} => {:a #{:b}, :b #{}}"
  [g]
  (let [have-incoming (apply union (vals g))]
    (reduce #(if (get % %2) % (assoc % %2 #{})) g have-incoming)))

(defn kahn-sort
  "Proposes a topological sort for directed graph g using Kahn's
   algorithm, where g is a map of nodes to sets of nodes. If g is
   cyclic, returns nil."
  ([g]
     (kahn-sort (normalize g) [] (no-incoming g)))
  ([g l s]
     (if (empty? s)
       (when (every? empty? (vals g)) l)
       (let [[n s'] (take-1 s)
             m (g n)
             g' (reduce #(update-in % [n] without %2) g m)]
         (recur g' (conj l n) (union s' (intersection (no-incoming g') m)))))))

;;==========================================



(declare build-options-paths)

(defn build-option-paths [path option]
  (let [new-path (conj path (::key option))
        child-options (::options option)
        option-value (::value option)
        result (cond-> {::t/path new-path}
                 option-value (assoc ::value option-value))]
    (if (seq child-options)
      (conj (build-options-paths new-path child-options)
            result)
      result)))

(defn build-options-entry-value-paths [path value]
  (if (sequential? value)
    (map (partial build-option-paths path) value)
    [(build-option-paths path value)]))

(defn build-options-entry-paths [path [option-key value]]
  (let [new-path (conj path option-key)]
    (build-options-entry-value-paths new-path value)))

(defn build-options-paths [path options]
  (map (partial build-options-entry-paths path) options))

(defn flatten-options [options]
  (flatten (build-options-paths [] options)))

(declare get-template-selection-path)

(defn get-template-option-path [selection [f & r] current-path]
  (let [[option option-i]
        (first (keep-indexed
                (fn [i s]
                  (if (= (::t/key s) f)
                    [s i]))
                (::t/options selection)))
        next-path (vec (concat current-path [::t/options option-i]))]
    (if (seq r)
      (get-template-selection-path option r next-path)
      next-path)))

(defn get-template-selection-path [template [f & r] current-path]
  (let [[selection selection-i]
        (first (keep-indexed
                (fn [i s]
                  (if (= (::t/key s) f)
                    [s i]))
                (::t/selections template)))
        next-path (vec (concat current-path [::t/selections selection-i]))]
    (if (seq r)
      (get-template-option-path selection r next-path)
      next-path)))

(defn get-lazy [a k]
  (if (and (seq? a) (int? k))
    (first (drop k a))
    (get a k)))

(defn get-in-lazy [m ks]
  (reduce get-lazy m ks))

(defn collect-plugins [flat-options plugin-map]
  (mapcat
   (fn [{path ::t/path
         option-value ::value
         :as option}]
     (::t/plugins (get-in plugin-map path)))
   flat-options))

(defn modifier-functions [modifiers]
  (map
   (fn [{:keys [::mods/value ::mods/fn ::mods/deferred-fn]}]
     (if (and deferred-fn value)
       (deferred-fn value)
       fn))
   modifiers))

(defn index-of-option [selection option-key]
  (first
   (keep-indexed
    (fn [i v]
      (if (= option-key (::key v))
        i))
    selection)))

(defn template-item-with-key [items item-key]
  (first
   (keep-indexed
    (fn [i s]
      (if (= (::t/key s) item-key)
        [i s]))
    items)))

(defn entity-item-with-key [items item-key]
  (first
   (keep-indexed
    (fn [i s]
      (if (and item-key
               (= (::key s) item-key))
        [i s]))
    items)))

(defn get-entity-path
  ([template entity option-path]
   (get-entity-path template entity [] option-path))
  ([template entity current-path [selection-k option-k & ks :as option-path]]
   (if selection-k
     (let [[selection-i selection] (template-item-with-key (::t/selections template) selection-k)
           {:keys [::t/min ::t/max ::t/options ::t/multiselect?]} selection
           [option-i option] (template-item-with-key options option-k)
           selection-path (vec (concat current-path [::options selection-k]))
           entity-items (get-in entity selection-path)
           [entity-i _] (entity-item-with-key entity-items option-k)
           path-i (if (and (or option-k entity-i)
                           (or (nil? max)
                               (> max 1)
                               multiselect?))
                    (if (nat-int? option-k)
                      option-k
                      entity-i))
           full-path (if path-i (conj selection-path path-i) selection-path)]
       (get-entity-path
        option
        entity
        full-path
        ks))
     (vec current-path))))

(defn order-modifiers [modifiers order]
  (let [order-map (zipmap order (range (count order)))]
    (sort-by (comp order-map ::mods/key) modifiers)))

(defn combine-ref-selections [selections]
  (let [first-selection (first selections)]
    (if first-selection
      (assoc
       first-selection
       ::t/min (apply + (map ::t/min selections))
       ::t/max (if (every? ::t/max selections) (apply + (map ::t/max selections)))
       ::t/options (into (sorted-set-by #(< (::t/key %) (::t/key %2))) (apply concat (map ::t/options selections)))))))

(defn combine-selections [selections]
  (let [by-ref (group-by ::t/ref selections)
        non-ref-selections (get by-ref nil)
        combined-ref-selections (map
                                 (fn [[_ ref-selections]]
                                   (combine-ref-selections ref-selections))
                                 (dissoc by-ref nil))]
    (sort-by (fn [s] [(or (::t/order s) 1000) (::t/name s)])
             (concat non-ref-selections combined-ref-selections))))

(defn get-all-selections-aux [path {:keys [::t/ref ::t/key ::t/selections ::t/options] :as obj} parent selected-option-paths]
  (let [children (map
                  (fn [{:keys [::t/key] :as s}]
                    (let [child-path (conj path key)]
                      (get-all-selections-aux child-path
                                              s
                                              obj
                                              selected-option-paths)))
                  (or selections options))]
    (cond
      selections
      (if (get-in selected-option-paths path)
        children)
      
      options
      (if key
        (concat
         [(assoc obj ::path path ::parent parent)]
         children)
        children))))

(defn remove-disqualified-selections [selections built-char]
  (remove #(or (nil? %)
               (let [prereq-fn (::t/prereq-fn %)]
                 (and prereq-fn (not (prereq-fn built-char)))))
          selections))

(defn get-all-selections [path obj selected-option-paths built-char]
  (remove-disqualified-selections
   (flatten (get-all-selections-aux path obj nil selected-option-paths))
   built-char))

(defn make-path-map-aux [character]
  (let [flat-options (flatten-options (::options character))]
    (reduce
     (fn [m v]
       (update-in m (::t/path v) (fn [c] (or c {}))))
     {}
     flat-options)))

(def memoized-make-path-map-aux (memoize make-path-map-aux))

(defn make-path-map [character]
  (memoized-make-path-map-aux character))

(defn available-selections [raw-entity built-entity template]
  (let [path-map (make-path-map raw-entity)
        all-selections (get-all-selections [] template path-map built-entity)]
    all-selections))

(defn tagged-selections [available-selections tags]
  (filter
   #(seq (intersection tags (::t/tags %)))
   available-selections))

(defn make-ref-selection-map [raw-entity template]
  (let [path-map (make-path-map raw-entity)
        all-selections (remove nil? (flatten (get-all-selections-aux [] template nil path-map)))
        by-ref (group-by ::t/ref all-selections)]
    (reduce-kv
     (fn [m ref selections]
       (if ref
         (assoc m (if (sequential? ref) ref [ref]) (combine-ref-selections selections))
         m))
     {}
     by-ref)))

(defn get-modifiers [template ref-selection-map path]
  (let [selection-path (butlast path)
        option-key (last path)
        ref-selection (ref-selection-map selection-path)
        option (if ref-selection
                 (first
                  (filter
                   (fn [{:keys [::t/key] :as option}]
                     (= option-key key))
                   (vec (::t/options ref-selection))))
                 (let [template-path (get-template-selection-path template path [])]
                   (get-in-lazy template template-path)))]
    (::t/modifiers option)))

(defn collect-modifiers [raw-entity flat-options template]
  (let [ref-selection-map (make-ref-selection-map raw-entity template)]
    (mapcat
     (fn [{path ::t/path
           option-value ::value
           :as option}]
       (let [modifiers (get-modifiers template ref-selection-map path)]
         (map
          (fn [{:keys [::mods/name ::mods/value ::mods/fn ::mods/deferred-fn ::mods/default-value] :as mod}]
            (if deferred-fn
              (assoc mod ::mods/value (or option-value default-value))
              mod))
          (flatten modifiers))))
     flat-options)))

(def memoized-make-modifier-map (memoize t/make-modifier-map))

(defn apply-options [raw-entity template]
  (let [options (flatten-options (::options raw-entity))
        modifiers (sort-by ::mods/order (collect-modifiers raw-entity options template))
        deps (reduce
              (fn [m {:keys [::mods/key ::mods/deps]}]
                (if (seq deps)
                  (update m key union deps)
                  m))
              {}
              modifiers)
        base (merge (::t/base template)
                    (::values raw-entity))
        base-deps (::es/deps base)
        all-deps (merge-with union deps base-deps)
        mod-order (rseq (kahn-sort all-deps))
        ordered-mods (order-modifiers modifiers mod-order)]
    (mods/apply-modifiers base ordered-mods)))

(defn build-aux [raw-entity template]
  (apply-options raw-entity template))

(def memoized-build-aux (memoize build-aux))

(defn build [raw-entity template]
  (memoized-build-aux raw-entity template))

(declare merge-selections)

(defn merge-options [options other-options]
  (if (or options other-options)
    (let [opt-map (zipmap (map ::t/key options) options)
          other-opt-map (zipmap (map ::t/key other-options) other-options)
          merged (merge-with
                  (fn [o1 o2]
                    (assoc
                     o1
                     ::t/selections (merge-selections (::t/selections o1) (::t/selections o2))
                     ::t/modifiers (vec (concat (::t/modifiers o1) (::t/modifiers o2)))))
                  opt-map
                  other-opt-map)]
      (vec
       (concat
        (map
         (fn [{key ::t/key}]
           (merged key))
         options)
        (vals (apply dissoc merged (map ::t/key options))))))))

(defn merge-selections [selections other-selections]
  (if (or selections other-selections)
    (let [sel-map (zipmap (map ::t/key selections) selections)
          other-sel-map (zipmap (map ::t/key other-selections) other-selections)
          merged (merge-with
                  (fn [s1 s2]
                    (assoc
                     s1
                     ::t/options
                     (merge-options (::t/options s1) (::t/options s2))))
                  sel-map
                  other-sel-map)]
      (vec
       (concat
        (map
         (fn [{key ::t/key}]
           (merged key))
         selections)
        (vals (apply dissoc merged (map ::t/key selections))))))))

(defn merge-multiple-selections [& selections]
  (reduce
   merge-selections
   selections))

(declare sort-selections)

(defn sort-options [s]
  (update
   s
   ::t/options
   (fn [options]
     (vec
      (sort-by
       (juxt ::t/order ::t/name)
       (map
        sort-selections
        options))))))

(defn sort-selections [o]
  (update
   o
   ::t/selections
   (fn [selections]
     (vec
      (sort-by
       (fn [selection]
         (or (::t/order selection) 1000))
       (map
        sort-options
        selections))))))

(defn build-template-aux [plugins template]
  (reduce
   (fn [templ {:keys [::t/path ::t/selections ::t/modifiers] :as plugin}]
     (let [template-path (get-template-selection-path templ path [])]
       (update-in
        templ
        template-path
        #(assoc
          %
          ::t/selections (merge-selections (::t/selections %) selections)
          ::t/modifiers (concat (::t/modifiers %) modifiers)))))
   template
   plugins))

(def memoized-build-template-aux (memoize build-template-aux))

(defn build-template [raw-entity template]
  (let [plugin-map (memoized-make-modifier-map template)
        options (flatten-options (::options raw-entity))
        plugins (collect-plugins options plugin-map)]
    (memoized-build-template-aux plugins template)))

(spec/fdef
 build
 :args (spec/cat :raw-entity ::raw-entity :modifier-map ::t/template)
 :ret any?)

(defn name-to-kw [name]
  (-> name
      clojure.string/lower-case
      (clojure.string/replace #"\W" "-")
      keyword))

(defn get-option-value-path [template entity path]
  (conj (get-entity-path template entity path) ::value))
