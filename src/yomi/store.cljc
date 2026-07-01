(ns yomi.store
  "SSoT for the yomi (読み — news-INTELLIGENCE) actor — the intel-assessment
  state of a news-intelligence actor that subscribes to kawaraban's public
  mirror (outlets · articles · mentions), reads the full text kawaraban holds
  only in its internal buffer (never in the public Datom log, G4), runs an
  intel-LLM ⊣ IntelGovernor, and publishes attributed intel assessments. The
  store is behind a `Store` protocol so the backend is a swap (MemStore default
  ‖ DatomicStore via langchain.db, itself swappable to real Datomic Local /
  kotoba-server).

  Domain = two regions:

    PUBLIC mirror region (subscribed from kawaraban, recorded via the observe
    ops — these are kawaraban's surfaced 面, read-only here):
      outlet  — a real-world news outlet a kawaraban 面 mirrors: kind
                (∈ :public-broadcaster / :wire-agency / :newspaper / :magazine
                / :digital-native / :ngo-press), access (∈ :open /
                :registration-wall — G4: paywall/terminal feeds are not
                representable kawaraban sources), country, lang, homepage.
      article — a kawaraban-mirrored article: kind (∈ :mirror / :actor-event),
                section, outlet, canonical url, headline, ≤280-char excerpt,
                as-of (epoch), lang, sourcing. Never a verdict (G1) and never
                the body (G4) — those fields are not part of this record.
      mention — a :news.mention edge: article × target (∈ :actor / :entity) ×
                role (∈ :subject / :source / :mentioned / :affected /
                :responding). This is the actor-to-actor wire kawaraban carries.

    PRIVATE intel region (yomi's OWN authored output — NEVER written to a public
    Datom log via these calls):
      fulltext   — the cached full text of an article, injected from kawaraban's
                   internal buffer (the body never enters the public log; yomi
                   reads it here, transiently, to ground an assessment).
      assessment — a committed intel assessment (yomi's attributed output).
      ledger     — the append-only intel genealogy — immutable provenance of
                   every assessment yomi publishes.

  Charter (ADR-0001): as-of epochs are integer seconds; credibility / priority
  are 0..1 scored ratios (doubles) — the only non-integers in the actor, kept
  because they are scored ratios rather than ledger genealogy; EAVT ground
  datoms are canonical; the append-only **ledger is the assessment genealogy** —
  immutable intel provenance (article → outlet → sourcing → assessment), the
  property a SaaS or a mutable DB row can't give you. The actor is observe →
  assess → publish only: it never publishes an assessment the IntelGovernor
  would reject, and it performs NO real-world actuation (ADR-2606281500 —
  publication is autonomous, publication ≠ actuation)."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (outlet [s id])
  (all-outlets [s])
  (article [s id])
  (all-articles [s])
  (mention [s id])
  (mentions-of [s id] "mentions for an article (the actor-to-actor wire)")
  (fulltext-of [s id] "cached full text for an article (private; injected from kawaraban buffer)")
  (assessment-of [s id] "committed intel assessment, or nil")
  (ledger [s])
  (record-datom! [s record] "append a mirror edge or private intel fact to the SSoT")
  (append-ledger! [s fact]  "append one immutable assessment-genealogy fact")
  (seed! [s data]           "bulk-seed entity collections (idempotent upsert)"))

;; ───────────────────────── demo data ─────────────────────────

(defn demo-data
  "Five outlets + five articles exercising every IntelGovernor branch:
    out-nhk      clean open public-broadcaster (NHK-like) → Governor passes →
                 phase-3 auto-publish (credibility 0.85, priority 0.6).
    out-reuters  clean open wire agency → Governor passes (0.85 / 0.6).
    out-chrono   registration-wall newspaper (access :registration-wall, still
                 mirror-able per G4) → Governor passes (0.75 / 0.5).
    out-blog     open digital-native, thin sourcing → low credibility/priority
                 → publish-gate HOLD (:low-credibility / :low-priority).
    out-paywall  (不良) paywalled newspaper — access ∉ {:open :registration-wall};
                 kawaraban cannot mirror it (G4) → HARD HOLD (:non-open-fulltext)."
  []
  {:outlets
   {"out-nhk"
    {:id "out-nhk" :name "日本放送協会" :country "JP" :lang "ja"
     :kind :public-broadcaster :access :open :homepage "https://www.nhk.or.jp/"}
    "out-reuters"
    {:id "out-reuters" :name "Reuters" :country "US" :lang "en"
     :kind :wire-agency :access :open :homepage "https://www.reuters.com/"}
    "out-chrono"
    {:id "out-chrono" :name "The Chronicle" :country "US" :lang "en"
     :kind :newspaper :access :registration-wall
     :homepage "https://example.com/chronicle"}
    "out-blog"
    {:id "out-blog" :name "(低信頼)どこかのブロガー" :country "JP" :lang "ja"
     :kind :digital-native :access :open :homepage "https://blog.example/"}
    "out-paywall"
    {:id "out-paywall" :name "(不良)有料壁の新聞" :country "US" :lang "en"
     :kind :newspaper :access :paywall :homepage "https://paywall.example/"}}
   :articles
   {"art-nhk-1"
    {:id "art-nhk-1" :kind :mirror :section "politics" :outlet "out-nhk"
     :url "https://www.nhk.or.jp/policy/2026/article-1"
     :headline "議会、再生可能エネルギー補助金を可決"
     :excerpt "議会は28日、太陽・風力補助金の拡大法案を可決した。"
     :as-of 1779600000 :lang "ja" :sourcing [:wire :official]}
    "art-reuters-1"
    {:id "art-reuters-1" :kind :mirror :section "world" :outlet "out-reuters"
     :url "https://www.reuters.com/world/2026/article-1"
     :headline "Central bank holds rate, signals patience"
     :excerpt "The central bank kept its policy rate unchanged on Tuesday."
     :as-of 1779600120 :lang "en" :sourcing [:wire]}
    "art-chrono-1"
    {:id "art-chrono-1" :kind :mirror :section "business" :outlet "out-chrono"
     :url "https://example.com/chronicle/business/2026/01"
     :headline "Shipping line reports record Q4"
     :excerpt "The Chronicle obtained quarterly filings showing record volume."
     :as-of 1779600240 :lang "en" :sourcing [:filing :wire]}
    "art-blog-1"
    {:id "art-blog-1" :kind :mirror :section "opinion" :outlet "out-blog"
     :url "https://blog.example/post/2026-rumor"
     :headline "(未確証)某社で噂の噂"
     :excerpt "ある匿名投稿によると〜、とあるブロガーが書いている。"
     :as-of 1779600360 :lang "ja" :sourcing [:anonymous]}
    "art-paywall-1"
    {:id "art-paywall-1" :kind :mirror :section "finance" :outlet "out-paywall"
     :url "https://paywall.example/finance/2026/leak"
     :headline "Bank records surface in leak"
     :excerpt "(取得不能 — 有料壁の向こう)"
     :as-of 1779600480 :lang "en" :sourcing [:leak]}}
   :mentions
   {"men-nhk-1"
    {:id "men-nhk-1" :article "art-nhk-1" :target "ent-renewable-subsidy"
     :target-kind :entity :role :subject}
    "men-nhk-2"
    {:id "men-nhk-2" :article "art-nhk-1" :target "act-diet"
     :target-kind :actor :role :responding}
    "men-reuters-1"
    {:id "men-reuters-1" :article "art-reuters-1" :target "ent-central-bank"
     :target-kind :entity :role :subject}}
   ;; PRIVATE cache — the article body is injected here from kawaraban's
   ;; internal buffer. It NEVER enters the public Datom log (kawaraban G4).
   ;; art-paywall-1 has no cache entry (the body is behind a paywall kawaraban
   ;; cannot mirror, so there is nothing to inject).
   :fulltexts
   {"art-nhk-1" "議会は28日午後、太陽光および風力発電に対する補助金の拡大法案を可決した。改正法は2027年度から適用され、既存のFIT制度を整理しつつ地域ごとの導入目標を設定する。賛成多数で可決、反対12。"
    "art-reuters-1" "The central bank kept its benchmark rate unchanged at its Tuesday meeting, citing mixed data. Officials signaled patience on cuts. Minutes will be released next week."
    "art-chrono-1" "The Chronicle obtained quarterly filings showing the shipping line moved record volume in Q4. Revenue rose 12% year on year. Executives attributed the gain to rerouted lanes."
    "art-blog-1" "ある匿名投稿によると、とある企業で噂があるという。根拠は示されていない。"}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (outlet [_ id] (get-in @a [:outlets id]))
  (all-outlets [_] (sort-by :id (vals (:outlets @a))))
  (article [_ id] (get-in @a [:articles id]))
  (all-articles [_] (sort-by :id (vals (:articles @a))))
  (mention [_ id] (get-in @a [:mentions id]))
  (mentions-of [_ id]
    (->> (:mentions @a) vals (filter #(= id (:article %)))
         (sort-by :id) vec))
  (fulltext-of [_ id] (get-in @a [:fulltexts id]))
  (assessment-of [_ id] (get-in @a [:assessments id]))
  (ledger [_] (:ledger @a))
  (record-datom! [s {:keys [kind id value]}]
    (case kind
      :outlet      (swap! a update-in [:outlets id] merge value)
      :article     (swap! a update-in [:articles id] merge value)
      :mention     (swap! a assoc-in [:mentions id] value)
      :fulltext    (swap! a assoc-in [:fulltexts id] value)
      :assessment  (swap! a assoc-in [:assessments id] value)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (seed! [s data] (swap! a merge (select-keys data
                                              [:outlets :articles :mentions
                                               :fulltexts])) s))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :assessments {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────────

(def ^:private schema
  {:outlet/id           {:db/unique :db.unique/identity}
   :article/id          {:db/unique :db.unique/identity}
   :mention/id          {:db/unique :db.unique/identity}
   :ledger/seq          {:db/unique :db.unique/identity}
   :assessment/article  {:db/valueType :db.type/ref :db/unique :db.unique/identity}
   :fulltext/article    {:db/valueType :db.type/ref :db/unique :db.unique/identity}
   :rec/article         {:db/valueType :db.type/ref}})   ; mention rows

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

;; The store talks to its backend ONLY through the langchain.db `:db-api` map
;; {:q :transact! :db :pull :entid}. langchain.db/api (in-process EAVT) and
;; langchain.kotoba-db/kotoba-api (kotoba-server XRPC, e.g. kotobase.net) both
;; implement it, so the same record runs on either by construction.

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- pull* [{:keys [api conn]} pattern eid] ((:pull api) ((:db api) conn) pattern eid))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defrecord DatomicStore [api conn]
  Store
  (outlet [this id]
    (when-let [m (pull* this [:outlet/id :outlet/edn] [:outlet/id id])]
      (when (:outlet/id m) (dec* (:outlet/edn m)))))
  (all-outlets [this]
    (->> (q* this '[:find [?id ...] :where [?e :outlet/id ?id]])
         (map #(outlet this %)) (sort-by :id)))
  (article [this id]
    (when-let [m (pull* this [:article/id :article/edn] [:article/id id])]
      (when (:article/id m) (dec* (:article/edn m)))))
  (all-articles [this]
    (->> (q* this '[:find [?id ...] :where [?e :article/id ?id]])
         (map #(article this %)) (sort-by :id)))
  (mention [this id]
    (dec* (q* this '[:find ?p . :in $ ?mid :where
                     [?e :mention/id ?mid] [?e :mention/edn ?p]] id)))
  (mentions-of [this id]
    (->> (q* this '[:find [?v ...] :in $ ?aid :where
                    [?e :article/id ?aid] [?r :rec/article ?e]
                    [?r :rec/kind :mention] [?r :rec/edn ?v]] id)
         (mapv dec*)))
  (fulltext-of [this id]
    (dec* (q* this '[:find ?p . :in $ ?aid :where [?e :article/id ?aid]
                     [?x :fulltext/article ?e] [?x :fulltext/edn ?p]] id)))
  (assessment-of [this id]
    (dec* (q* this '[:find ?p . :in $ ?aid :where [?e :article/id ?aid]
                     [?x :assessment/article ?e] [?x :assessment/edn ?p]] id)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (record-datom! [s {:keys [kind id value]}]
    (case kind
      :outlet      (tx* s [{:outlet/id id :outlet/edn (enc value)}])
      :article     (tx* s [{:article/id id :article/edn (enc value)}])
      :mention     (tx* s [{:mention/id id :mention/edn (enc value)}
                           {:rec/article [:article/id (:article value id)]
                            :rec/kind :mention :rec/edn (enc value)}])
      :fulltext    (tx* s [{:fulltext/article [:article/id id]
                            :fulltext/edn (enc value)}])
      :assessment  (tx* s [{:assessment/article [:article/id id]
                            :assessment/edn (enc value)}])
      nil)
    s)
  (append-ledger! [s fact]
    (tx* s [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}]) fact)
  (seed! [s data]
    (doseq [[id o]  (:outlets data)]   (record-datom! s {:kind :outlet   :id id :value o}))
    (doseq [[id a]  (:articles data)]  (record-datom! s {:kind :article  :id id :value a}))
    (doseq [[id m]  (:mentions data)]  (record-datom! s {:kind :mention  :id id :value m}))
    (doseq [[id ft] (:fulltexts data)] (record-datom! s {:kind :fulltext :id id :value ft}))
    s))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (default Datomic-
  shaped store; verifiable offline). For the kotoba-server pod (kotobase.net),
  bind the same record to langchain.kotoba-db/kotoba-api — same record, different
  :db-api (see ADR-0001 / docs/DESIGN.md)."
  ([] (datomic-store nil))
  ([data] (let [s (->DatomicStore d/api (d/create-conn schema))]
            (when data (seed! s data)) s)))

(defn datomic-seed-db [] (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line [{:keys [op article disposition basis]}]
  (str/join " · " [(name (or disposition :record)) (str "op=" op)
                   (str "article=" article) (str "basis=" (pr-str basis))]))
