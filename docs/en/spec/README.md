# Feature Specs

Feature-organized "current shape" docs. Each spec describes how one feature works **today** — file paths, schema, public contract, current cardinality. For the *why* behind a design choice, follow the ADR references at the bottom of each spec.

## Index

| Spec | Covers | Linked ADRs |
|---|---|---|
| [chart-editor.md](../../../.claude/docs/spec/chart-editor.md) | Chart authoring surface (palette + canvas + history + save flow) | ADR-007, 008, 009, 011, 013 |
| [suggestion-flow.md](../../../.claude/docs/spec/suggestion-flow.md) | Suggestion (PR) open / list / detail / comment / close / apply / conflict resolution | ADR-012, 013, 014 |
| [collaboration-history.md](../../../.claude/docs/spec/collaboration-history.md) | `chart_versions` append-only spine, version history / variants / comparison / restore | ADR-007, 013 |
| [symbol-pack-delivery.md](symbol-pack-delivery.md) | Dynamic symbol pack delivery — catalog manifest, payload storage, entitlement gate, sync manager, render-time lookup | ADR-016 |

> The first three specs above live at `.claude/docs/spec/` for historical reasons (they were authored as agent context). New specs land under `docs/en/spec/`. Both are linked from this index; treat them as a single conceptual directory.

## When a spec is the right tool

Use a spec when the question is "**what does this feature look like in `main` today**" — file paths, the data spine, the public contract, the gotchas a contributor would otherwise re-discover by greppning.

Do NOT use a spec for:
- Why a design choice was made → ADR.
- Step-by-step procedures for operating the feature → [ops/](../ops/).
- Per-symbol authoring conventions or other deep reference material → dedicated reference docs (e.g. `symbol-review/`).

## Spec hygiene

- A spec is the **single non-test artifact** to update when implementation drifts from documentation. Update in the same commit that drifts the implementation. The ADR only updates if the *decision* changed.
- Specs should be skim-friendly. Tables for file maps, schema, contracts. Diagrams for data flow. Prose only where structure is insufficient.
- Specs link out to vendor / framework documentation rather than restating it. Restating creates tech debt as the vendor doc evolves.
- A spec under ~500 lines is healthy. Past that, consider splitting (per the existing chart-editor / suggestion-flow / collaboration-history split).

## Cross-references

- System-level current shape: [docs/en/architecture.md](../architecture.md)
- Operator runbooks: [docs/en/ops/README.md](../ops/README.md)
- Decision rationale: [docs/en/adr/](../adr/)
