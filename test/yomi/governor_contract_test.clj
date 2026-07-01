(ns yomi.governor-contract-test
  "The intel-discipline contract as executable tests — yomi's analog of
  sng's governor_contract_test / robotaxi's safety_contract_test /
  itonami's governor_contract_test / kyoninka's governor_contract_test.
  Invariant: the actor never publishes an assessment the IntelGovernor would
  reject, never publishes a low-credibility/low-priority assessment, never
  grounds intel on a source kawaraban cannot mirror (G4), and always records
  observations."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [yomi.store :as store]
            [yomi.intelllm :as intelllm]
            [yomi.synthesis :as syn]))

(defn- fresh [] (let [s (store/seed-db)] [s (syn/build s)]))
(defn- ctx [phase] {:phase phase})

(defn- run [actor tid req phase]
  (g/run* actor {:request req :context (ctx phase)} {:thread-id tid}))

(defn- last-basis [s] (-> (store/ledger s) last :basis))

(deftest ingest-always-records
  (testing "observe path records a mirror edge regardless of phase"
    (let [[s actor] (fresh)
          res (run actor "i" {:op :mention/register :mention "men-new"
                              :value {:id "men-new" :article "art-nhk-1"
                                      :target "ent-x" :target-kind :entity
                                      :role :mentioned}} 0)]
      (is (= :record (get-in res [:state :disposition])))
      (is (some #(= "men-new" (:id %)) (store/mentions-of s "art-nhk-1"))))))

(deftest clean-open-publish-auto-commits
  (testing "a clean open public-broadcaster article auto-publishes in phase 3 (no interrupt)"
    (let [[s actor] (fresh)
          res (run actor "p" {:op :publish :article "art-nhk-1"} 3)]
      (is (not (= :interrupted (:status res))) "publication is autonomous — no interrupt")
      (is (= :publish (get-in res [:state :disposition])))
      (is (= :high-confidence-open-source
             (:classification (store/assessment-of s "art-nhk-1"))))
      (is (= :auto (:by (store/assessment-of s "art-nhk-1")))))))

(deftest non-open-fulltext-is-held
  (testing "art-paywall-1: paywall outlet — kawaraban cannot mirror (G4)"
    (let [[s actor] (fresh)
          res (run actor "a" {:op :analyze :article "art-paywall-1"} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:non-open-fulltext} (last-basis s)))
      (is (nil? (store/assessment-of s "art-paywall-1")) "nothing recorded on hold"))))

(deftest low-credibility-publish-is-held
  (testing "art-blog-1: low-credibility digital-native — publish gate HOLD"
    (let [[s actor] (fresh)
          res (run actor "p" {:op :publish :article "art-blog-1"} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:low-credibility} (last-basis s)))
      (is (nil? (store/assessment-of s "art-blog-1"))))))

(deftest unsourced-claim-is-held
  (testing "a proposal with empty :sources — HOLD (:unsourced-claim)"
    (let [[s _] (fresh)
          bad-adv (reify intelllm/Advisor
                    (-advise [_ _ _ _]
                      {:classification :needs-corroboration :facts [] :findings []
                       :entities {} :credibility 0.9 :priority 0.9
                       :source-family :forged :collection-method :none
                       :analytic-lens :none :summary "x"
                       :sources [] :provenance-chain [{:article "art-nhk-1"}]
                       :effect :assessment :confidence 0.9}))
          a2 (syn/build s {:advisor bad-adv})
          res (g/run* a2 {:request {:op :publish :article "art-nhk-1"} :context (ctx 3)}
                      {:thread-id "u"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:unsourced-claim} (last-basis s))))))

(deftest no-actuation-invariant
  (testing "a proposal that tries to actuate / direct is held (:no-actuation)"
    (let [[s _] (fresh)
          bad-adv (reify intelllm/Advisor
                    (-advise [_ _ _ _]
                      {:classification :high-confidence-open-source :facts [] :findings []
                       :entities {} :credibility 0.9 :priority 0.9
                       :source-family :wire-agency :collection-method :kawaraban-mirror
                       :analytic-lens :open-source-synthesis :summary "x"
                       :sources [{:outlet "out-nhk"} {:article "art-nhk-1"}]
                       :provenance-chain [{:article "art-nhk-1" :outlet "out-nhk"}]
                       :effect :launch-strike :confidence 0.9}))
          a2 (syn/build s {:advisor bad-adv})
          res (g/run* a2 {:request {:op :publish :article "art-nhk-1"} :context (ctx 3)}
                      {:thread-id "na"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-actuation} (last-basis s))))))

(deftest phase0-disables-assessments
  (testing "a :publish in the path-reserved phase is held (phase-disabled)"
    (let [[s actor] (fresh)
          res (run actor "p0" {:op :publish :article "art-nhk-1"} 0)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= :phase-disabled (-> (store/ledger s) last :phase-reason))))))
