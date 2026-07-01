(ns yomi.governor
  "IntelGovernor — the independent intel-discipline layer that earns the
  intel-LLM the right to *publish*. The LLM has no binding notion of which
  sources close the provenance chain, of the open/registration-wall access
  constraint (kawaraban G4 — yomi cannot ground intel on a source kawaraban
  cannot mirror), of the Murakumo-only model fleet (G2), of libel risk, or of
  the no-actuation charter, so this MUST be a separate system (rules over the
  EAVT mirror + the proposal map) able to *reject* a proposal and fall back to
  HOLD — the yomi analog of robotaxi's MRC / itonami's airworthiness hold /
  sng's CarbonGovernor / kyoninka's PermitGovernor.

  Charter (ADR-0001): the actor is **observe → assess → publish only**. It
  never publishes an assessment the IntelGovernor would reject, never grounds
  intel on a source kawaraban cannot mirror (G4), never fabricates a finding,
  and performs NO real-world actuation (ADR-2606281500 — publication ≠
  actuation). Per ADR-2606281500 news publication is AUTONOMOUS BY DEFAULT, so
  unlike sng's pathway/select there is NO Council interrupt for :publish — the
  Governor's sourcing/provenance/access/libel/fleet gates + the publish
  credibility/priority floors + the Rider §2 catastrophe-veto content scan are
  the bound, and a clean assessment auto-publishes in phase 3.

  HARD invariants (ADR-0001, kawaraban G2/G4):
    1. Sources cited        — a proposal with empty :sources is unsourced.
    2. Provenance chain     — a proposal with no :provenance-chain cannot be
                              grounded.
    3. Open/registration    — the outlet :access must be ∈ {:open
                              :registration-wall}; kawaraban cannot mirror a
                              paywall/terminal feed (G4), so yomi cannot ground
                              intel on it either.
    4. No libel             — a proposal flagged :defamatory? is held.
    5. Murakumo-only model  — when :model-host is present it must be in the
                              fleet allowlist (G2); a non-fleet model is held.
    6. No-actuation         — the proposal writes an :assessment, never a
                              real-world actuation / instruction / directive.
  PUBLISH gate (op :publish only):
    7. Credibility floor    — credibility < 0.7 → HOLD (:low-credibility).
    8. Priority floor       — priority < 0.45 → HOLD (:low-priority).
  SOFT:
    9. Confidence floor (0.6) → escalate on :analyze. yomi has no Council, so
       an escalate folds to HOLD in the graph (there is no human signoff path
       for an autonomous publication actor — the assessment is simply not
       published).

  yomi performs no actuation, so :high-stakes? is ALWAYS FALSE — publication is
  autonomous (ADR-2606281500), bounded by these gates + the catastrophe-veto
  content scan.

  Op scope: :analyze and :publish both check the HARD sourcing/provenance/
  access/libel/fleet/actuation invariants (those are what an intel assessment
  IS); :publish additionally enforces the credibility/priority publish floors."
  (:require [yomi.store :as store]))

(def confidence-floor 0.6)
(def publish-credibility-floor 0.7)
(def publish-priority-floor 0.45)

;; ───────────────────────── ADR-derived constants ─────────────────────────

(def allowed-access #{:open :registration-wall})                 ; kawaraban G4
(def allowed-model-hosts                                         ; Murakumo fleet, G2
  #{"murakumo-litellm" "127.0.0.1:4000" "litellm.murakumo.svc" "localhost:4000"})

;; ───────────────────────── invariant checks ─────────────────────────

(defn- source-violations [proposal]
  (cond-> []
    (empty? (:sources proposal))
    (conj {:rule :unsourced-claim
           :detail "提案に :sources が無い — 根拠なき主張は出せない"})
    (empty? (:provenance-chain proposal))
    (conj {:rule :missing-provenance
           :detail ":provenance-chain が無い — 出所連鎖をGroundingできない"})))

(defn- access-violations [st request]
  ;; kawaraban G4: only :open / :registration-wall public facing pages are
  ;; representable mirror sources. yomi cannot ground intel on anything else.
  (let [art (store/article st (:article request))
        out (store/outlet st (:outlet art))]
    (when (and out (not (allowed-access (:access out))))
      [{:rule :non-open-fulltext
        :detail (str "outlet " (:id out) " access=" (:access out)
                     " ∉ {:open :registration-wall} — kawaraban G4 で mirror 不能な"
                     " 情報源に intel を grounding することはできない")}])))

(defn- libel-violations [proposal]
  (when (:defamatory? proposal)
    [{:rule :libel-risk
      :detail "提案に :defamatory? true — 名誉毀損リスク、即HOLD"}]))

(defn- model-violations [proposal]
  ;; checked only when :model-host is present (real LLM tag); the mock advisor
  ;; leaves it absent, so this is inert in the R0 skeleton.
  (when-let [host (:model-host proposal)]
    (when (not (allowed-model-hosts host))
      [{:rule :non-fleet-model
        :detail (str "model-host " host " は Murakumo fleet allowlist 外 (G2)")}])))

(defn- actuation-violations [proposal]
  ;; observe→assess→publish: the actor may write an :assessment datom, never a
  ;; real-world actuation / instruction / directive.
  (when (not= :assessment (:effect proposal))
    [{:rule :no-actuation
      :detail (str "actor は実世界の作動/指令をしない(publication≠actuation)。effect="
                   (:effect proposal))}]))

(defn- publish-gate-violations [request proposal]
  ;; The credibility/priority floors bind the PUBLISH op (an assessment yomi
  ;; attributes to itself and surfaces). :analyze is held to the lower confidence
  ;; floor only (SOFT → escalate → hold).
  (when (= :publish (:op request))
    (cond-> []
      (< (get proposal :credibility 0.0) publish-credibility-floor)
      (conj {:rule :low-credibility
             :detail (str "credibility " (:credibility proposal) " < "
                          publish-credibility-floor " — publish gate")})
      (< (get proposal :priority 0.0) publish-priority-floor)
      (conj {:rule :low-priority
             :detail (str "priority " (:priority proposal) " < "
                          publish-priority-floor " — publish gate")}))))

(defn check
  "Censors an intel-LLM proposal for a yomi op. Returns
   {:ok? :violations :confidence :hard? :escalate? :high-stakes?}.

   Hard violations force HOLD and cannot be overridden by a human. yomi performs
   no actuation, so :high-stakes? is always FALSE — publication is autonomous
   (ADR-2606281500), bounded by these gates + the catastrophe-veto scan. There
   is no Council interrupt for :publish (unlike sng's pathway/select)."
  ([request proposal st] (check request proposal st nil))
  ([request proposal st _opts]
   (let [hard (vec (concat (source-violations proposal)
                           (access-violations st request)
                           (libel-violations proposal)
                           (model-violations proposal)
                           (actuation-violations proposal)
                           (publish-gate-violations request proposal)))
         conf  (:confidence proposal 0.0)
         low?  (< conf confidence-floor)
         hard? (boolean (seq hard))]
     {:ok?          (and (not hard?) (not low?))
      :violations   hard
      :confidence   conf
      :hard?        hard?
      :escalate?    (and (not hard?) low?)
      :high-stakes? false})))     ; yomi performs no actuation

(defn hold-fact [request verdict]
  {:t :intel-hold :op (:op request) :article (:article request)
   :disposition :hold :basis (mapv :rule (:violations verdict))
   :violations (:violations verdict) :confidence (:confidence verdict)})
