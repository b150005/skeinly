# Critic Checklist & Primary-Source Allowlist

Detailed reference for the `research-critic` agent. Loaded on demand from
`SKILL.md`. The Critic must apply the 10-item checklist below to every claim
the Generator emits, and may cite only sources from the allowlist.

## Critic checklist (10 items)

Apply each item to every claim. Mark severity per `SKILL.md` §"Severity
classification".

1. **Primary source present.** At least one citation in the claim points to
   the primary-source allowlist below. If only secondary sources are cited
   → MEDIUM (Generator must add primary).
2. **Version pinning.** Citation URL is version-tagged or commit-pinned —
   not a moving "latest" pointer that will change next release. Untagged
   citations on volatile APIs → HIGH.
3. **Retrieval date.** Citation carries `(retrieved: YYYY-MM-DD)`. Missing
   → LOW (annotate, do not block).
4. **Tool-family independence.** Critic verified using a different tool
   family than the Generator (Context7 vs. direct URL fetch vs. `gh`
   against vendor repo). Same family + same query → HIGH.
5. **Conditional claims explicit.** "Works only when X" claims state X.
   Unstated conditions ("works in Node.js" assumed but not declared) → HIGH.
6. **Argument names match types/source.** For function/method claims,
   argument names and types match the primary source's type definition or
   source code. Mismatch → CRITICAL (likely hallucination).
7. **Default values match source.** Where defaults are claimed, they match
   the primary source. Mismatch → HIGH.
8. **Breaking-change awareness.** If the cited version is N, claims about
   behaviour are valid for N — not silently inherited from N-1. Implicit
   inheritance → HIGH.
9. **No internal contradiction.** Generator's own citations do not
   contradict each other. If they do, at least one is wrong → MEDIUM,
   Generator must reconcile.
10. **Hallucination check.** URL resolves (200, not 404). Function/method
    appears in primary source's actual API surface. Failed resolution or
    no match in primary source → CRITICAL.

## Primary-source allowlist

The Critic's independent citation must come from one of the categories
below. Each row gives the rationale and a URL pattern.

| Category | URL pattern (example) | Rationale |
|---|---|---|
| Vendor official docs | `nextjs.org/docs/...`, `flutter.dev/docs/...`, `pub.dev/documentation/...`, `pkg.go.dev/...` | Authoritative, version-aware, maintained by the vendor |
| Vendor official GitHub README | `github.com/<vendor>/<repo>/blob/<tag>/README.md` | Tied to a specific release tag |
| Vendor official CHANGELOG | `github.com/<vendor>/<repo>/blob/<tag>/CHANGELOG.md` | Authoritative breaking-change record |
| Vendor source / types | `github.com/<vendor>/<repo>/blob/<tag>/<path>` | The code is the contract; type definitions are checkable |
| Vendor official examples | `github.com/<vendor>/<repo>/blob/<tag>/examples/...` | Maintained alongside the code |
| Vendor issue tracker | `github.com/<vendor>/<repo>/issues/<n>` | For known-bug confirmation only — not for design questions |
| Standards bodies | `datatracker.ietf.org/doc/html/rfc...`, `w3.org/TR/...`, `tc39.es/ecma...` | Normative, slow-moving |
| MDN Web Docs | `developer.mozilla.org/docs/Web/...` | Authoritative for Web-platform APIs only — not for framework or library APIs |
| Language stdlib | `docs.python.org/3/...`, `pkg.go.dev/std`, `doc.rust-lang.org/std/...` | Maintained by the language team |

## Tier 1.5 — issuing-regulator official interpretive guidance

Per ADR-013 (verification-layer-wide scope), a closed allowlist of
issuing-regulator official interpretive guidance is admissible
**only** when the question under review intersects a
delegated-regulator domain (e.g., GDPR DPIA mechanics, PPC breach
notification thresholds, Apple Privacy Manifest required reasons),
and **only** paired with a Tier 1 citation on the same item. Tier 1.5
alone is invalid output and the Critic must reject it.

Closed Tier 1.5 allowlist (fixed at the ADR layer; extending it
requires a new ADR, not a checklist edit):

| Regulator | Document classes | Enabling delegation |
|---|---|---|
| EDPB | Guidelines, Recommendations, Opinions | GDPR Art. 70(1)(e) |
| 個人情報保護委員会 (PPC) | ガイドライン, Q&A, 通達 | 個人情報保護法 §147–§149 |
| California Privacy Protection Agency (CPPA) | Regulations | CCPA §1798.185 |
| Apple | Privacy Manifest specification, Required Reasons API documentation | First-party platform authority |
| Google | Play User Data policy, SDK Index documentation | First-party platform authority |

Excluded from Tier 1.5 (these remain disqualifying as below):
regulator blog posts, press releases, staff op-eds, social-media
posts, FAQ landing pages, conference slides. Tier 1.5 admits only
formal instruments issued under the regulator's enabling-statute
authority.

Topics outside delegated-regulator domains (general framework /
library / language questions) continue to require Tier 1 only.
Tier 1.5 does not apply to React, Next.js, Flutter, Go, Rust, etc.,
because those domains have no enabling regulator.

See [ADR-013] for the full sub-rules (closed allowlist, pairing
rule, authority floor, stale-guidance handling, scope).

[ADR-013]: ../../../meta/adr/013-invariant-2-source-tier-model.md

## Not acceptable as Critic's independent citation

Listed explicitly because the Critic's whole purpose is to catch the lag
between primary and secondary sources.

- Stack Overflow, Qiita, Zenn, dev.to, Medium
- Personal or company blogs (even if technically accurate; freshness is
  not enforced and authority is not verifiable)
- AI summary sites, cached snippets, screenshots, search-engine
  knowledge panels
- Tutorial repositories that are not the vendor's own
- Translations of primary sources (use the original — translations lag
  and may drift from the source)
- Generator's own citations — by definition not independent

## Edge cases

### Tutorial trees on vendor sites (`<vendor>/learn/...`, `<vendor>/getting-started/...`)

Many vendors host both `/docs/` (reference) and `/learn/` (tutorial)
trees on the same domain. **Treat tutorial trees as secondary**, even
when same-domain as primary docs. Tutorials are written for
introduction, not normative reference, and they often pin to an older
version than the current `/docs/`. The Critic's independent citation
must come from `/docs/` or the equivalent reference tree, not
`/learn/`, `/getting-started/`, `/tutorial/`, or `/blog/` paths.

### Vendor docs are genuinely poor

Some libraries have sparse docs and the source is the only authoritative
record. In that case, cite the source file at a specific tag/SHA. Do not
relax to a blog "because the docs don't cover it."

### Primary source contradicts itself across pages

Pick the version-tagged page over an undated page. If both are tagged but
disagree, file the contradiction as the Critic finding (HIGH); the
Generator must resolve which is current.

### Question is about idiomatic style, not API correctness

Style questions are T3 in the Tier table — Critic is not invoked. If a
style question is escalated to T2/T1 because it affects a design decision,
the Critic still requires a primary-source citation (e.g., the language's
official style guide, the vendor's contributor guide).

### Generator and Critic both rely on the same primary source

This is acceptable — the constraint is "different tool family", not
"different source". The shared primary source is the agreed oracle. If
that oracle is itself wrong, no two-sided review can catch it; that risk
is accepted (see ADR-008 §Consequences).

## See also

- [`SKILL.md`](./SKILL.md) — protocol overview
- [`failure-modes.md`](./failure-modes.md) — patterns the checklist is
  designed to catch
- [`.claude/agents/research-critic.md`](../../agents/research-critic.md)
  — Critic agent definition
