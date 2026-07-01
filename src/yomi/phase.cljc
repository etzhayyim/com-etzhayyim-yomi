(ns yomi.phase
  "R0→R3 staged rollout (ADR-0001 §roadmap), gating only the ASSESS ops
  (intel assessments). Recording subscribed kawaraban mirror edges (the observe
  function) is always on — that is yomi's charter (durable EAVT observations of
  the mirror). The phase only decides how much autonomy the *assessments* have,
  and can only add caution.

    R0 path-reserved — record mirror edges; emit NO assessments yet.
    R1 bench pilot   — assessments allowed, but always human review (no auto).
    R2 assisted      — :analyze may auto-commit when clean+confident; :publish
                       still waits (out-of-auto → escalate → hold; no council).
    R3 supervised    — :analyze AND :publish auto-commit when the IntelGovernor
                       passes (ADR-2606281500 — publication is autonomous; NO
                       council signoff).")

(def record-ops #{:outlet/register :article/register :mention/register})
(def assess-ops #{:analyze :publish})

(def phases
  {0 {:label "path-reserved" :assess #{}        :auto #{}}
   1 {:label "bench-pilot"   :assess assess-ops :auto #{}}
   2 {:label "assisted"      :assess assess-ops :auto #{:analyze}}
   3 {:label "supervised"    :assess assess-ops :auto #{:analyze :publish}}})

(def default-phase 3)

(defn record-op? [op] (contains? record-ops op))

(defn gate
  "Adjust an assess op's governor disposition for the rollout phase.
  Returns {:disposition kw :reason kw|nil}. :publish is in :auto only at R3,
  so an R2 :publish escalates (which folds to HOLD in the graph — yomi has no
  council signoff path, ADR-2606281500)."
  [phase {:keys [op]} disposition]
  (let [{:keys [assess auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold disposition)        {:disposition :hold :reason nil}
      (not (contains? assess op))  {:disposition :hold :reason :phase-disabled}
      (and (= :commit disposition)
           (not (contains? auto op))) {:disposition :escalate :reason :phase-approval}
      :else                        {:disposition disposition :reason nil})))

(defn verdict->disposition [v]
  (cond (:hard? v) :hold (:escalate? v) :escalate :else :commit))
