(ns yomi.synthesis
  "IntelActor — one yomi operation = one supervised actor run, a langgraph-clj
  StateGraph. Two flows share one auditable graph:

    ingest (record path):  intake → record → END
        subscribed kawaraban mirror edges (outlet · article · mention) become
        durable EAVT ground datoms. This is the observe charter; always on,
        never an LLM call, never an actuation.

    assess path:  intake → fetch-fulltext → advise → govern → decide → publish|hold
        the intel-LLM (sealed) proposes an intel assessment grounded in the
        article's mirrored metadata + the cached full text (read transiently
        from kawaraban's internal buffer, G4 — never from the public log); the
        IntelGovernor enforces the sourcing / provenance / open-access / libel
        / fleet-model / no-actuation invariants and the publish
        credibility/priority gates; the phase gate adds caution; a clean
        assessment auto-publishes in phase 3 (ADR-2606281500 — publication is
        autonomous; NO council signoff).

  Single invariant (the yomi analog of sng's carbon contract):
    the actor never publishes an assessment the IntelGovernor would reject,
    and performs no real-world actuation.

  Per ADR-2606281500 there is NO :request-approval node and `interrupt-before`
  is empty — publication is autonomous (publication ≠ actuation), bounded by
  the Governor + the Rider §2 catastrophe-veto content scan."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [yomi.intelllm :as intelllm]
            [yomi.governor :as gov]
            [yomi.phase :as phase]
            [yomi.store :as store]))

(defn- request->record
  "Map an ingest request to a store ground-datom record (a subscribed mirror
  edge — observe path)."
  [{:keys [op outlet article mention value]}]
  (case op
    :outlet/register   {:kind :outlet  :id outlet  :value value}
    :article/register  {:kind :article :id article :value value}
    :mention/register  {:kind :mention :id mention :value value}))

(defn- subject [{:keys [article outlet mention]}] (or article outlet mention))

(defn build
  "Compiles an IntelActor bound to `store` (any yomi.store/Store).
  opts: :advisor (default mock), :checkpointer (default in-mem)."
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (intelllm/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; :phase (+ future authn)
         :fulltext    {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; ── ingest path: record a subscribed mirror edge (observe) ──
      (g/add-node :record
        (fn [{:keys [request]}]
          (let [rec (request->record request)
                f   {:t :recorded :op (:op request) :article (subject request)
                     :disposition :record :basis (:kind rec)}]
            (store/record-datom! store rec)
            (store/append-ledger! store f)
            {:disposition :record :audit [f]})))

      ;; ── assess path ──
      ;; read the cached full text (private; injected from kawaraban's buffer).
      (g/add-node :fetch-fulltext
        (fn [{:keys [request]}]
          (let [ft (store/fulltext-of store (:article request))]
            {:fulltext ft
             :audit [{:t :fulltext-fetched :article (:article request)
                      :has-fulltext? (boolean ft)}]})))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (intelllm/-advise advisor store nil request)]
            {:proposal p :audit [(intelllm/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request proposal]}]
          {:verdict (gov/check request proposal store)}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (gov/hold-fact request verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              ;; yomi has NO council signoff path (publication is autonomous,
              ;; ADR-2606281500). A low-confidence / out-of-phase escalate
              ;; folds to HOLD — the assessment is simply not published.
              {:disposition :hold
               :audit [(merge (gov/hold-fact request verdict)
                              {:t :escalate-held :disposition :hold
                               :reason (or reason :low-confidence)
                               :phase ph})]}

              :commit
              {:disposition :publish
               :record {:kind :assessment :id (subject request)
                        :value (assoc proposal :article (:article request)
                                         :by :auto)}}))))

      ;; publish an assessment datom + ledger (assess path only). The ledger
      ;; :disposition is the op (:publish / :analyze) so an :analyze auto-commit
      ;; and a :publish auto-commit are distinguishable in the genealogy.
      (g/add-node :publish
        (fn [{:keys [request record]}]
          (store/record-datom! store record)
          (let [f {:t :assessed :op (:op request) :article (subject request)
                   :disposition (:op request)
                   :basis (get-in record [:value :classification])}]
            (store/append-ledger! store f)
            {:disposition (:op request) :audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:intel-hold :escalate-held} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      ;; intake routes ingest vs assess.
      (g/add-conditional-edges :intake
        (fn [{:keys [request]}]
          (if (phase/record-op? (:op request)) :record :fetch-fulltext)))
      (g/add-edge :fetch-fulltext :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (if (= :publish disposition) :publish :hold)))

      (g/set-finish-point :record)
      (g/set-finish-point :publish)
      (g/set-finish-point :hold)

      ;; ADR-2606281500 — publication is autonomous; no human-in-the-loop seam.
      (g/compile-graph
       {:checkpointer checkpointer :interrupt-before #{}})))
