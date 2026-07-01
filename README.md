# com-etzhayyim-yomi （読み — News Intelligence artificial organism）

A **news-INTELLIGENCE artificial organism** — the actor that subscribes to the
**kawaraban** news mirror, reads the full text kawaraban holds only in its
internal buffer (never in the public Datom log, G4), runs an **intel-LLM ⊣
IntelGovernor**, and publishes **attributed intel assessments**. yomi is a
**SOURCE / VOICE** (it authors assessments in its own voice); kawaraban stays
the **MEDIUM** (it mirrors the world and wires the actors, authoring nothing).
Per **ADR-2606281500** (種をまく doctrine), publication is **AUTONOMOUS BY
DEFAULT** — not per-post operator/Council gated — bounded by the IntelGovernor,
the credibility/priority publish gates, and the Rider §2 catastrophe-veto
content scan. yomi performs **no real-world actuation** (publication ≠
actuation).

Platform vocabulary:

- **kotoba** is the sovereign data/compute substrate: CID, Datom log, WASM,
  auth and network primitives.
- **kototama** is the common organism/actor platform and runtime adapter layer.
- **app-aozora** is the AT Protocol product boundary: PDS, AppView, XRPC,
  lexicons, feeds/search and profile publication.
- **com-etzhayyim-yomi** is the domain organism. It may surface as an AT
  Protocol actor, but it does not run its own PDS.

The current runnable topology uses
[`langgraph-clj`](../../com-junkawasaki/langgraph-clj) StateGraph as the
orchestration backend (portable `.cljc`, supervised run, `interrupt-before`
human-in-the-loop, Datomic/in-mem checkpoints), in the same governed shape as
the reference actors: **robotaxi-actor** (AR1 ⊣ SafetyGovernor) /
**gftd-talent-actor** (HR-LLM ⊣ PolicyGovernor) / **ai-gftd-itonami**
(ops-LLM ⊣ CertGovernor) / **com-etzhayyim-kyoninka** (reg-LLM ⊣
PermitGovernor) / **com-etzhayyim-sng** (synth-LLM ⊣ CarbonGovernor).

> **Why an actor layer?** "What does this reporting mean?" is not a model
> question — it is a question of binding intel discipline: whether every finding
> cites a source kawaraban can actually mirror (open / registration-wall, G4 —
> never a paywalled body), whether facts are separated from findings, whether
> the provenance chain closes, whether the credibility/priority floors are met,
> whether the model ran on the Murakumo fleet (G2), whether the piece is
> libelous, and whether someone is trying to slip an actuation through a
> "news" actor. A language model can *advise* on all of this; it must never be
> the thing that says "published." This project seals the intel advisor
> (intel-LLM) into one node and wraps it with an independent **IntelGovernor**
> that enforces those invariants and the autonomous-publish doctrine.

See [`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md).

## The core contract

```
kawaraban mirror (outlet · article · mention) + cached full text (private buffer)
        │
        ▼
   ┌──────────┐    proposal     ┌────────────────┐
   │ intel-LLM│ ──────────────▶ │ IntelGovernor  │  (independent system)
   │ (sealed) │  assessment +   │ intel invariants│
   └──────────┘  cited sources  └───────┬────────┘
                            publish/hold ◀┘
                               │
                            attributed
                            intel assessment
                            (append-only ledger)
```

**The actor never publishes an assessment the IntelGovernor would reject, and
performs no real-world actuation** — observe → assess → publish only. That
single invariant is the yomi analog of sng's carbon contract. Per
ADR-2606281500 publication is autonomous: there is **no Council signoff** for
`:publish`, only the Governor + the catastrophe-veto scan.

## Demo articles (illustrative)

| id | outlet | access | outcome |
|----|--------|--------|---------|
| `art-nhk-1` | `out-nhk` public-broadcaster | `:open` | clean → auto-publish (phase 3, credibility 0.85) |
| `art-reuters-1` | `out-reuters` wire agency | `:open` | clean → auto-publish (0.85) |
| `art-chrono-1` | `out-chrono` newspaper | `:registration-wall` | allowed per G4 → auto-publish (0.75) |
| `art-blog-1` | `out-blog` digital-native | `:open` | low credibility → publish-gate HOLD (`:low-credibility`) |
| `art-paywall-1` | `out-paywall` newspaper | `:paywall` | HARD HOLD (`:non-open-fulltext`, kawaraban G4) |

The mirror rulebook is **data** (`yomi.store/demo-data`): an outlet, an article,
or a mention edge is an EAVT ground datom, not a code change.

## Run

```bash
clojure -M:dev:run     # drive assessments through one IntelActor
clojure -M:dev:test    # the intel contract as executable tests
clojure -M:lint        # clj-kondo (errors fail)
```

Demo walks: ingest a new outlet+article → `art-nhk-1` clean open
public-broadcaster (Governor passes → phase-3 auto-publish) → `art-chrono-1`
registration-wall (allowed) → `art-blog-1` low-credibility digital-native
(publish-gate HOLD) → `art-paywall-1` paywall outlet (HARD HOLD,
`:non-open-fulltext` G4) → phase-0 path-reserved (held) → the append-only intel
ledger → the same contract on `DatomicStore`.

## Layout

| File | Actor / role |
|---|---|
| `src/yomi/store.cljc` | SSoT — outlets · articles · mentions (kawaraban mirror) · cached fulltext · assessments; `MemStore` ‖ `DatomicStore` (langchain.db `:db-api`); append-only ledger |
| `src/yomi/intelllm.cljc` | **intel-LLM** — the contained intelligence node (intel advisor); mock ‖ real LLM via `langchain.model` |
| `src/yomi/governor.cljc` | **IntelGovernor** — independent intel invariants; HOLD on unsourced-claim, missing-provenance, non-open-fulltext, libel-risk, non-fleet-model, no-actuation; publish-gate HOLD on low-credibility/low-priority |
| `src/yomi/phase.cljc` | R0→R3 staged rollout (path-reserved → supervised); `:publish` auto at R3 (no council) |
| `src/yomi/synthesis.cljc` | **IntelActor** — the langgraph-clj StateGraph (1 run = 1 op); no `:request-approval` node (autonomous) |
| `src/yomi/sim.cljc` | demo driver |
| `test/yomi/governor_contract_test.clj` | the intel invariant, executable |
| `test/yomi/store_contract_test.clj` | `MemStore ≡ DatomicStore` |

## Status

Reference design + runnable skeleton, R0. The demo outlets/articles/mentions
are illustrative (the mirror rulebook comes from kawaraban's 11 gates, above
all G1 mirror-not-adjudicator and G4 copyright/link-out), and the intel-LLM is
a deterministic mock. The **actor topology, the IntelGovernor invariants, the
autonomous-publish doctrine (ADR-2606281500), the R0→R3 phase gate, and the
append-only assessment-genealogy ledger are real and tested.** Productionizing
means (1) wiring the public mirror region to a live kawaraban subscription
(Council Lv6+ + operator gated — G8), (2) swapping
`intelllm/mock-advisor` for `llm-advisor` on a real `langchain.model` hosted on
the Murakumo LiteLLM fleet (G2), and (3) optionally binding the store to
kotoba-server (kotobase.net) so the ledger is an actor-signed CACAO graph
(see ADR-0001).
