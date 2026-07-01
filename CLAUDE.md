# yomi 読み — CLAUDE instructions

News-INTELLIGENCE actor (a SOURCE / VOICE). ADR-2606281500 (種をまく doctrine)
+ ADR-0001. **Read the root `/CLAUDE.md` Charter + substrate rules first.**
yomi-specific invariants below make the Charter concrete for this actor; they
weaken nothing.

## The one-sentence identity

yomi is a **news-INTELLIGENCE actor, kotoka-wasm-native, on the Murakumo fleet**:
it **subscribes to kawaraban's** public mirror (outlets · articles · mentions),
**reads the full text** kawaraban holds only in its internal buffer (never the
public Datom log, G4), runs an **intel-LLM ⊣ IntelGovernor**, and **publishes
attributed intel assessments** in its own voice. Where kawaraban is the MEDIUM
(mirrors the world, authors nothing), yomi is a SOURCE — it *says* things, on
the record, with provenance.

## Autonomous publication — do NOT gate it per-post

Per **ADR-2606281500** (種をまく doctrine), publication is **AUTONOMOUS BY
DEFAULT**. A clean assessment (`:publish` op, Governor passes, credibility ≥ 0.7,
priority ≥ 0.45) **auto-publishes in phase 3 with NO operator/Council signoff**.
There is deliberately **no `:request-approval` / `interrupt-before` node** in
`yomi.synthesis` (unlike sng's pathway/select). Do not add one. The bound on
autonomous publication is the **IntelGovernor + the credibility/priority publish
gates + the Rider §2 catastrophe-veto content scan** — not a human-in-the-loop.

## The IntelGovernor invariants — do NOT weaken

`yomi.governor/check` returns `{:ok? :violations :confidence :hard? :escalate?
:high-stakes?}`. `:high-stakes?` is **always FALSE** for yomi (no actuation).
HARD violations force HOLD and cannot be overridden by a human:

- **G-sources** `:unsourced-claim` — proposal `:sources` empty.
- **G-provenance** `:missing-provenance` — no `:provenance-chain`.
- **G4 access** `:non-open-fulltext` — outlet `:access` ∉ `#{:open
  :registration-wall}`. kawaraban cannot mirror a paywall/terminal feed, so yomi
  cannot ground intel on it either. Touch the outlet `:access` enum in lockstep
  with kawaraban's schema/lexicon.
- **G-libel** `:libel-risk` — proposal `:defamatory?` true.
- **G2 fleet** `:non-fleet-model` — `:model-host` (when present) must be in the
  Murakumo allowlist.
- **G-no-actuation** `:no-actuation` — `:effect` must be `:assessment`. yomi
  publishes intel; it never actuates, directs, or instructs (publication ≠
  actuation).

PUBLISH gate (op `:publish` only): `:low-credibility` (< 0.7) and
`:low-priority` (< 0.45) are HOLD. SOFT: confidence floor 0.6 → escalate on
`:analyze` (folds to HOLD in the graph — yomi has no council).

## When editing

- `interrupt-before` is `#{}` on purpose (autonomous publication). A future
  catastrophe-veto seat does NOT route through a langgraph interrupt; it is a
  content scan the Governor calls before publish. Keep the graph interrupt-free.
- `.cljc` portability: wrap `clojure.edn` / `Exception` in `#(?clj/:cljs)`.
- The PUBLIC mirror region (outlet/article/mention) is subscribed FROM
  kawaraban — recording a mirror edge here is the observe charter, always on.
  The PRIVATE region (fulltext cache, assessments, ledger) is yomi's OWN output
  and is never written to a public Datom log via these calls.
- Tests are standalone-runnable (`clojure -M:dev:test`) AND clj-kondo-clean
  (`clojure -M:lint`, errors fail). Keep them so.

## Siblings / boundaries

- **kawaraban 瓦版** — the MEDIUM. yomi subscribes to its mirror and reads its
  internal fulltext buffer; yomi is the SOURCE that authors assessments.
- **ake 朱 / danjo 弾正** — own truth-correction and discrepancy. yomi publishes
  intel *assessments* with a credibility score; it does not adjudicate ground
  truth (an ake correction of a mirrored fact is still ake's, not yomi's
  verdict).
- **shirabe 調べ** — the read-only Q&A membrane. yomi *publishes* attributed
  assessments (write voice); shirabe *answers* questions (read membrane). Don't
  cross the two.
- **kataribe 語部** — etzhayyim's OWN press (first-person primary voice). yomi's
  voice is the analyst's, not the press officer's.
