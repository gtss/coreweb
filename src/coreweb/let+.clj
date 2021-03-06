(ns coreweb.let+
  (:use coreweb.request
        coreweb.special))

(defn- assoc-&-binding [binds req sym]
  (assoc binds sym `(dissoc (:params ~req)
                      ~@(map keyword (keys binds))
                      ~@(map str (keys binds)))))

(defn- assoc-symbol-binding [binds req sym]
  (assoc binds sym `(get-in ~req [:params ~(keyword sym)]
                      (get-in ~req [:params ~(str sym)]))))

(defn- vector-bindings
  "Create the bindings for a vector of parameters."
  [args req]
  (loop [args args, binds {}]
    (if-let [sym (first args)]
      (cond
        (= '& sym)
        (recur (nnext args) (assoc-&-binding binds req (second args)))
        (= :as sym)
        (recur (nnext args) (assoc binds (second args) req))
        (symbol? sym)
        (recur (next args) (assoc-symbol-binding binds req sym))
        :else (throw (Exception. (str "Unexpected binding: " sym))))
      (mapcat identity binds))))

(defmacro let+
  ([new-name old-name pred domacro]
    `(defmacro ~new-name [bindings# & body#]
       (if (~pred [bindings# body#])
         `(~'~domacro ~bindings# ~@body#)
         `(~'~old-name ~bindings# ~@body#))))
  ([new-name old-name pred [iv ibody] & body]
    `(defmacro ~new-name [bindings# & body#]
       (if (~pred [bindings# body#])
         (let [~iv bindings# ~ibody body#]
           ~@body)
         `(~'~old-name ~bindings# ~@body#)))))

(let+ let-request let (comp vector? first first)
  [[bindings request] body]
  `(let [~@(vector-bindings bindings request)] ~@body))

(defmacro comp-body-binding [[bindings request] & body]
  (let [temp (if (and (some #{:as '&} bindings) (map? (last bindings)))
               (into (subvec bindings 0 (dec (count bindings))) (keys (last bindings))) ;map's order not guaranteed
               bindings)
        args (remove #{:as '&} temp)]
    `(let [~@(vector-bindings bindings request)]
       ((comp ~@body) ~@args))))

(let+ let-request+ coreweb.let+/let-request
  #(and (-> % ffirst vector?) (-> % last last symbol?) (-> % last last resolve meta :arglists nil? not))
  coreweb.let+/comp-body-binding)

(let+ let-request++ coreweb.let+/let-request+ #(and (-> % ffirst integer?) (-> % last last symbol?))
  [[n request] body]
  (let [operation (last `(~@body))
        fix-bindings #(if (vector? %) (vec (remove #{'&} %)) %)
        maybe-bindings (nth (:arglists (meta (resolve operation))) n {})
        has-more (some #{'&} maybe-bindings)
        bindings (fix-bindings maybe-bindings)]
    `(let [~@(vector-bindings bindings request)]
       ~(cond (= {} maybe-bindings) `(do ~@body)
          has-more `(apply (comp ~@body) ~@bindings)
          :else `((comp ~@body) ~@bindings)))))

(let+ let-request+++ coreweb.let+/let-request++ #(-> % ffirst string?)
  [[s request] body]
  (let [bindings (read-string s)]
    `(let-request++ [~bindings (read-request-string ~request)] ~@body)))

(defmacro symbol-binding [[s request] & body]
  (check-expression (local-bindings) s :flat :constant)
  (if-let [bindings (resolve s)]
    `(let-request+++ [~(deref bindings) ~request]
       ~@body)
    (let [locals (local-bindings)
          local (or (locals s)  ((locals '&env) s))]
      `(let-request+++ [~(.eval (.init local)) ~request]
         ~@body))))

(let+ let-request++++ coreweb.let+/let-request+++ #(-> % ffirst symbol?) coreweb.let+/symbol-binding)