(ns yomi.store-contract-test
  "Backend-swap contract: MemStore ≡ DatomicStore. The same seed + the same
  reads must agree, and the actor's assess/publish verdicts must be identical on
  either backend — the property that lets the kotoba-server pod (kotobase.net)
  drop in via langchain.kotoba-db with the same record."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [yomi.store :as store]
            [yomi.synthesis :as syn]))

(defn- publish [actor tid aid]
  (g/run* actor {:request {:op :publish :article aid} :context {:phase 3}}
          {:thread-id tid}))

(deftest mem-and-datomic-reads-agree
  (testing "the two backends return equal domain reads for the demo data"
    (let [m (store/seed-db) d (store/datomic-seed-db)]
      (is (= (mapv :id (store/all-outlets m))
             (mapv :id (store/all-outlets d))))
      (is (= (mapv :id (store/all-articles m))
             (mapv :id (store/all-articles d))))
      (is (= (store/outlet m "out-nhk") (store/outlet d "out-nhk")))
      (is (= (store/article m "art-nhk-1") (store/article d "art-nhk-1")))
      (is (= (store/fulltext-of m "art-nhk-1") (store/fulltext-of d "art-nhk-1")))
      ;; multi-row child reads are order-independent (datalog has no inherent order)
      (is (= (set (store/mentions-of m "art-nhk-1"))
             (set (store/mentions-of d "art-nhk-1")))))))

(deftest verdicts-match-across-backends
  (testing "clean → publish on both; paywall → hold on both"
    (let [ma (syn/build (store/seed-db))
          da (syn/build (store/datomic-seed-db))]
      (is (= :publish
             (get-in (publish ma "m1" "art-nhk-1") [:state :disposition])
             (get-in (publish da "d1" "art-nhk-1") [:state :disposition])))
      (is (= :hold
             (get-in (publish ma "m2" "art-paywall-1") [:state :disposition])
             (get-in (publish da "d2" "art-paywall-1") [:state :disposition]))))))
