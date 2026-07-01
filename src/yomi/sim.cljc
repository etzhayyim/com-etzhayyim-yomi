(ns yomi.sim
  "Demo: drive news-intelligence assessment through one IntelActor.

    ingest              register an outlet + an article (observe → mirror datoms)
    analyze art-nhk-1   clean open public-broadcaster → IntelGovernor passes →
                        phase 3 auto-analyze
    publish art-nhk-1   credibility 0.85 ≥ 0.7, priority 0.6 ≥ 0.45 →
                        auto-publish (no council — ADR-2606281500)
    publish art-chrono-1 registration-wall newspaper (allowed) → auto-publish
    publish art-blog-1  low-credibility digital-native → HOLD (:low-credibility)
    analyze art-paywall-1 paywall outlet (∉ {:open :registration-wall}) →
                        HARD HOLD (:non-open-fulltext, kawaraban G4)
    phase 0             :publish in path-reserved → held (phase-disabled)
    backend swap        the same contract on DatomicStore

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [yomi.store :as store]
            [yomi.synthesis :as syn]))

(defn- line [& xs] (println (apply str xs)))

(defn- drive [actor tid req phase]
  (let [res (g/run* actor {:request req :context {:phase phase}} {:thread-id tid})]
    (line "   → " (get-in res [:state :disposition])
          (when-let [pr (-> res :state :audit last :phase-reason)] (str " (" pr ")")))
    res))

(defn -main [& _]
  (let [st    (store/seed-db)
        actor (syn/build st)]

    (line "── ingest (observe → kawaraban mirror datoms) ──")
    (drive actor "i1" {:op :outlet/register :outlet "out-extra"
                       :value {:id "out-extra" :name "追加分"
                               :kind :magazine :access :open :country "JP"
                               :lang "ja" :homepage "https://extra.example/"}} 3)
    (drive actor "i2" {:op :article/register :article "art-extra"
                       :value {:id "art-extra" :kind :mirror :section "culture"
                               :outlet "out-extra"
                               :url "https://extra.example/culture/1"
                               :headline "追加記事" :excerpt "メモ"
                               :as-of 1779600600 :lang "ja" :sourcing [:wire]}} 3)
    (line "  registered outlets: " (mapv :id (store/all-outlets st)))

    (line "\n── analyze art-nhk-1 (clean open public-broadcaster) ──")
    (drive actor "a-nhk" {:op :analyze :article "art-nhk-1"} 3)

    (line "\n── publish art-nhk-1 (credibility≥0.7 priority≥0.45 → 自動発信) ──")
    (drive actor "p-nhk" {:op :publish :article "art-nhk-1"} 3)

    (line "\n── publish art-chrono-1 (registration-wall、許容) ──")
    (drive actor "p-chr" {:op :publish :article "art-chrono-1"} 3)

    (line "\n── publish art-blog-1 (低信頼、publish gate HOLD) ──")
    (drive actor "p-blg" {:op :publish :article "art-blog-1"} 3)

    (line "\n── analyze art-paywall-1 (有料壁、kawaraban G4 違反 → HARD HOLD) ──")
    (drive actor "a-pay" {:op :analyze :article "art-paywall-1"} 3)

    (line "\n── 段階導入: publish を phase 0 (path-reserved) で ──")
    (drive actor "p-p0" {:op :publish :article "art-nhk-1"} 0)

    (line "\n── インテル台帳 (append-only; 評価系譜) ──")
    (doseq [f (store/ledger st)] (line "  " (store/ledger-line f)))

    (line "\n── バックエンド差し替え: DatomicStore でも同一契約 ──")
    (let [ds (store/datomic-seed-db) da (syn/build ds)]
      (drive da "d1" {:op :publish :article "art-nhk-1"} 3)
      (line "  DatomicStore assessment art-nhk-1 classification: "
            (:classification (store/assessment-of ds "art-nhk-1"))))
    (line "\ndone.")))
