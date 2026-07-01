# ADR-0001 — com-etzhayyim-yomi architecture (news-intelligence actor)

- Status: accepted
- Date: 2026-07-01
- Org: etzhayyim
- Relates to: ADR-2606281500 (種をまく doctrine — autonomous publication),
  ADR-2606061900 (kawaraban, the 11 gates), ADR-2605231902 (feed-post membrane);
  com-etzhayyim-sng (synth-LLM ⊣ CarbonGovernor), com-etzhayyim-kyoninka
  (reg-LLM ⊣ PermitGovernor), com-etzhayyim-kawaraban (the medium yomi
  subscribes to); `.cursor/rules/always/actor-pattern-rule.mdc`.

## Context

kawaraban (ADR-2606061900) is the news **medium**: it mirrors the world's real
outlets into the kotoba Datom log and wires etzhayyim actor to actor via
`:news.mention` edges. By charter kawaraban authors nothing in its own voice
(G11) and adjudicates nothing (G1) — it links out, it never says "this is true."
There is a seat missing from that picture: a **source** that reads what
kawaraban mirrors, reasons about it, and **publishes attributed intel
assessments** in its own voice — with credibility scores, separated facts vs
findings, and a closed provenance chain back to a mirror-able outlet.

ADR-2606281500 (種をまく doctrine) establishes that etzhayyim's seeds — news
publication among them — are **autonomous by default**: publication is not a
per-post operator/Council gate (publication ≠ actuation), bounded instead by an
independent governor + catastrophe-veto content scan. A news-intelligence actor
is the natural shape for that doctrine: it must read, reason, and publish under
binding intel discipline, without a human approving every post.

## Decision

Build yomi as a **standalone Tier-B actor** — another instance of the workspace
actor pattern (after robotaxi / gftd-talent / itonami / kyoninka / sng),
specialized as a news-INTELLIGENCE source. Concretely:

1. **Containment + independent governor + immutable ledger.** The intel advisor
   (**intel-LLM**) is sealed into one node and returns *proposals only* — an
   intel assessment map (facts separated from findings, entities,
   classification, credibility, priority, cited `:sources`, the
   `:provenance-chain`, `:effect :assessment`). An independent **IntelGovernor**
   censors every proposal against the sourcing / provenance / open-access /
   libel / fleet-model / no-actuation invariants (and the publish
   credibility/priority floors for `:publish`) and dispositions it publish /
   hold. Single invariant: *the actor never publishes an assessment the governor
   would reject, and performs no real-world actuation.* Every publish/hold/
   record appends to an append-only ledger (the assessment genealogy).

2. **langgraph-clj StateGraph, 1 run = 1 operation.** No unbounded inner loop.
   **There is no `:request-approval` node and `interrupt-before` is `#{}`** —
   publication is autonomous (ADR-2606281500); the bound is the Governor + the
   catastrophe-veto scan, not a human-in-the-loop seam. (This is the deliberate
   difference from sng, where a pathway/select always interrupts for a Council
   Lv6+≥3 signoff.)

3. **Three injection seams.** Store (`MemStore` ‖ `DatomicStore`), Advisor
   (`mock-advisor` ‖ `llm-advisor` on `langchain.model`, hosted on the Murakumo
   LiteLLM fleet — G2), Phase (R0→R3). The core is invariant under all three.

4. **Store is `:db-api`-driven, with two regions.**
   - **PUBLIC mirror region** (subscribed from kawaraban): `outlet`, `article`,
     `mention` — kawaraban's surfaced 面, recorded via the observe ops.
   - **PRIVATE intel region** (yomi's OWN authored output): the cached `fulltext`
     (injected from kawaraban's internal buffer — the body never enters the
     public Datom log, G4), `assessment`, and the append-only `ledger`.
   The store talks to its backend only through the langchain.db
   `{:q :transact! :db :pull :entid}` map; `langchain.db/api` and
   `langchain.kotoba-db/kotoba-api` both implement it, so the same record runs
   in-memory, on real Datomic, or on the kotoba-server pod. Enforced by a
   `MemStore ≡ DatomicStore` contract test.

5. **The mirror rulebook is data, not code.** Outlet kinds, access classes,
   section, sourcing, mention roles and target-kinds are attributes of EAVT
   ground datoms. Adding an outlet / article / mention is a
   `:outlet/register` / `:article/register` / `:mention/register` transaction
   (the observe charter) — no code change. `:access :paywall` is representable
   in the schema but the Governor rejects any assessment grounded on it
   (`:non-open-fulltext`) — kawaraban G4 excludes it as a mirror source by
   construction.

6. **Epoch `as-of` integers; credibility/priority/confidence as 0..1 scored
   ratios (the only non-integers)** — kept because they are scored ratios rather
   than ledger genealogy. Dates on articles are epoch-second ints; the whole
   actor stays `.cljc`-portable (JVM / SCI / cljs / WASM) with no date/decimal
   libraries.

## The IntelGovernor invariants

sources cited (`:sources` non-empty) · provenance chain present · outlet
`:access ∈ {:open :registration-wall}` (kawaraban G4) · no libel
(`:defamatory?` false) · Murakumo-only model (G2, when `:model-host` present) ·
no-actuation (`:effect :assessment`). Publish gate (`:publish` op only):
credibility ≥ 0.7, priority ≥ 0.45. SOFT: confidence floor 0.6 → escalate on
`:analyze` (folds to HOLD — no council). `:high-stakes?` is always FALSE (yomi
performs no actuation).

## Autonomous publication (ADR-2606281500)

A clean assessment auto-publishes in phase 3 with **no Council signoff**: there
is no `:request-approval` node, no `interrupt-before`. The bound on autonomous
publication is the IntelGovernor + the publish credibility/priority gates + the
Rider §2 catastrophe-veto content scan (a content scan the Governor calls before
publish, not a langgraph interrupt). This is the yomi analog of — and the
deliberate contrast with — sng's always-interrupt pathway/select.

## Consequences

- A clean open-source article auto-publishes in phase 3 (the Governor IS the
  guarantee); a deficient one is held with the exact violated rules in the
  ledger, and the hold cannot be overridden by a human (you cannot approve past
  an unsourced claim, a paywalled body, or libel).
- yomi never publishes a low-credibility / low-priority assessment — the publish
  gate holds it even when the sourcing is otherwise clean.
- The intel-LLM can be upgraded (or swapped to a real model on the Murakumo
  fleet) without touching the intel guarantees; the guarantees live in the
  governor and the data.
- The outlet/article/mention rulebook is illustrative until wired to a live
  kawaraban subscription (Council Lv6+ + operator gated — G8); because it is
  data, that wiring is a reviewed transaction, not a refactor.

## Follow-ups

- Register the repo in the west manifest via a single-entry GitHub-API clean
  commit (`manifest/repos.edn` → regenerate `west.yml`), pin == repo HEAD —
  same procedure as the other actors. RAD identity journal at
  `orgs/etzhayyim/root/80-data/kotoba-rad/yomi.identity.journal.edn`.
- Wire the PUBLIC mirror region to a live kawaraban subscription (G8-gated) and
  the PRIVATE fulltext cache to kawaraban's internal buffer injector.
- Add the Rider §2 catastrophe-veto content scan as a Governor-invoked check
  before publish (not a graph interrupt).
- Optional sovereign ledger on kotoba-server (kotobase.net): give the actor its
  own Ed25519 identity (`.yomi/identity.edn`, gitignored) and bind the store to
  `langchain.kotoba-db`, per `ai-gftd-itonami/src/itonami/cacao.clj`.
