(ns yomi.intelllm
  "intel-LLM — the contained intelligence node (intel advisor). It reads an
  article's kawaraban-mirrored metadata (outlet, headline, excerpt, sourcing,
  mentions) plus the cached full text (injected from kawaraban's internal
  buffer — the body never lives in the public Datom log, G4) and returns a
  PROPOSAL: an intel assessment (facts separated from findings, entities,
  classification, credibility, priority, cited sources, the provenance chain),
  never a real-world actuation and never a fabricated finding. Every output is
  censored by `yomi.governor` before anything is recorded/published, and — per
  ADR-2606281500 — publication is autonomous (publication ≠ actuation): there is
  no Council signoff, only the Governor + the catastrophe-veto scan.

  Advisor is injected (mock | real LLM via langchain.model), same as
  robotaxi.ar1 / talent.hrllm / itonami.opsllm / kyoninka.regllm / sng.synthllm.

  Proposal shape (modeled on etzhayyim-project-news intel.report + provenance
  scoring):
    {:facts []           ; what the source literally says (observed)
     :findings []        ; the analyst's read of those facts (inference)
     :entities {}        ; named entities / actors the article touches
     :classification kw  ; :high-confidence-open-source | :needs-corroboration
     :credibility 0..1   ; source + sourcing-method score
     :priority 0..1      ; newsworthiness / actor-relevance
     :source-family kw   ; outlet kind proxy
     :collection-method kw
     :analytic-lens kw
     :summary str
     :sources []         ; cited {:outlet .. :article ..}
     :provenance-chain [] ; article → outlet → access → sourcing
     :effect :assessment ; the actor only ever writes an assessment datom
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [yomi.store :as store]))

;; ───────────────────────── deterministic mock ─────────────────────────

(defn- score
  "The credibility/priority the mock assigns an outlet+fulltext, mirroring what
  the IntelGovernor will check, so a clean open public-broadcaster/wire yields
  :high-confidence-open-source (auto-publishes in phase 3) and a thin
  digital-native source yields :needs-corroboration / low credibility (the
  publish gate holds). A missing fulltext always demotes to corroboration-needed."
  [access kind ft]
  (cond
    (nil? ft)                       [0.45 0.3 :needs-corroboration]
    (= :open access)                (case kind
                                      (:public-broadcaster :wire-agency)
                                      [0.85 0.6 :high-confidence-open-source]
                                      :newspaper
                                      [0.8 0.55 :high-confidence-open-source]
                                      :digital-native
                                      [0.5 0.3 :needs-corroboration]
                                      [0.7 0.5 :high-confidence-open-source])
    (= :registration-wall access)   [0.75 0.5 :high-confidence-open-source]
    :else                           [0.45 0.3 :needs-corroboration]))

(defn- assess-article [st {:keys [article]}]
  (let [art    (store/article st article)
        out    (store/outlet st (:outlet art))
        ft     (store/fulltext-of st article)
        [cred pri cls] (score (:access out) (:kind out) ft)]
    {:facts          (cond-> []
                      ft (conj (str (:name out) " が見出し「" (:headline art)
                                    "」を as-of " (:as-of art) " に配信"))
                      ft (conj (str "sourcing=" (pr-str (:sourcing art)))))
    :findings       (if ft
                      [(str "正文は抜粋を裏付け、分類=" (name cls))]
                      ["正文取得不能 — 抜粋を裏付けられず(:needs-corroboration)"])
    :entities       {(:outlet art) {:role :source}}
    :classification cls
    :credibility    cred
    :priority       pri
    :source-family  (or (:kind out) :unknown)
    :collection-method :kawaraban-mirror
    :analytic-lens  :open-source-synthesis
    :summary        (str (:headline art)
                         (if ft " — 正文読解、信頼性スコア付き"
                             " — 正文不在、要補完"))
    :sources        (cond-> []
                      out (conj {:outlet (:id out)})
                      art (conj {:article (:id art)}))
    :provenance-chain [{:article (:id art) :outlet (:id out)
                        :access (:access out) :sourcing (:sourcing art)}]
    :effect         :assessment
    :confidence     cred}))

(defn infer [st _today {:keys [op] :as req}]
  ;; :analyze and :publish both produce the same intel assessment; the
  ;; difference is only whether the publish-gate (credibility/priority floors)
  ;; binds, which the Governor decides from (:op request).
  (case op
    :analyze (assess-article st req)
    :publish (assess-article st req)
    {:classification :needs-corroboration :facts [] :findings []
     :entities {} :credibility 0.0 :priority 0.0 :source-family :unknown
     :collection-method :none :analytic-lens :none :summary "未対応"
     :sources [] :provenance-chain [] :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────

(defprotocol Advisor
  (-advise [advisor store today request]))

(defn mock-advisor [] (reify Advisor (-advise [_ st today req] (infer st today req))))

(def ^:private system-prompt
  (str "あなたは etzhayyim のニュース・インテリジェンス助言者(yomi intel-LLM)です。"
       "kawaraban が mirror した記事の見出し・正文(内部bufferから注入)・outlet 情報のみに基づき、"
       "intel 評価を1つ EDN マップで返します。EDN だけを出力。\n"
       "キー: :facts(事実) :findings(所見) :entities :classification"
       "(:high-confidence-open-source|:needs-corroboration) :credibility(0..1)"
       " :priority(0..1) :source-family :collection-method :analytic-lens :summary"
       " :sources(outlet/article id を引用) :provenance-chain :effect(:assessment 固定)"
       " :confidence(0..1)。\n"
       "重要: 与えられた記事+正文+outlet に grounding されない主張は絶対に書かない(G4 非捏造)。"
       "出所が不足する場合は :classification :needs-corroboration とし、事実(facts)と所見(findings)を分離。"
       ":sources と :provenance-chain には outlet/article の id を必ず引用。"
       "実世界の作動/指令は決して提案しない(publication≠actuation)。"))

(defn- facts-for [st {:keys [article]}]
  (let [art (store/article st article)
        fid (:outlet art)]
    {:article  art
     :outlet   (store/outlet st fid)
     :fulltext (store/fulltext-of st article)
     :mentions (store/mentions-of st article)}))

(defn- parse-proposal [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :sources         #(vec (or % [])))
          (update :provenance-chain #(vec (or % [])))
          (update :facts           #(vec (or % [])))
          (update :findings        #(vec (or % [])))
          (update :entities        #(or % {}))
          (update :classification  #(or % :needs-corroboration))
          (update :credibility     #(if (number? %) (double %) 0.0))
          (update :priority        #(if (number? %) (double %) 0.0))
          (update :confidence      #(if (number? %) (double %) 0.0))
          (update :effect          #(or % :noop)))
      {:classification :needs-corroboration :facts [] :findings []
       :entities {} :credibility 0.0 :priority 0.0 :source-family :unknown
       :collection-method :none :analytic-lens :none :summary "LLM応答を解釈できません"
       :sources [] :provenance-chain [] :effect :noop :confidence 0.0})))

(defn llm-advisor
  "Advisor backed by a langchain.model/ChatModel (Anthropic / OpenAI-compatible
  / mock-model, hosted on the Murakumo LiteLLM fleet — G2). Output is parsed
  defensively → an unparseable response is a confidence-0 noop the governor
  will hold."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st _today req]
       (let [resp (model/-generate chat-model
                    [{:role :system :content system-prompt}
                     {:role :user :content (str "操作:" (:op req)
                                                " 記事:" (:article req)
                                                "\n事実:" (pr-str (facts-for st req)))}]
                    gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace [request proposal]
  {:t :intelllm-proposal :op (:op request) :article (:article request)
   :classification (:classification proposal) :credibility (:credibility proposal)
   :priority (:priority proposal) :summary (:summary proposal)
   :sources (:sources proposal) :confidence (:confidence proposal)})
