# Documentation

> Source-of-truth English docs. Japanese mirrors live at `docs/ja/`. Claude Code reads English only to minimize context window usage; human contributors may read either.

## Start here

- **Joining the project / coming back after a long break** → [architecture.md](architecture.md) — system-level current shape
- **Extending a feature** → [spec/](spec/) — feature-level current shape
- **Operational task** (publish content, cut a release, debug a failure) → [ops/README.md](ops/README.md)
- **Tracking decisions** → [adr/](adr/)

## Doc lanes

| Lane | Purpose | Where |
|---|---|---|
| **WHAT IS** | Current shape of the system / a feature | [architecture.md](architecture.md) (system) + [spec/](spec/) (per-feature) |
| **WHY** | Design rationale + alternatives considered | [adr/](adr/) |
| **WHAT TO DO** | Step-by-step operator runbooks | [ops/](ops/) |
| **HISTORICAL** | How we got here over time | [phase/](phase/) + ADR revision histories |

When implementation drifts from an ADR, update the spec (or `architecture.md`); only update the ADR if the *decision* changed. The ADR's `Revision history` block is the trail for decision-level changes.

## Reference docs (general)

| Document | Description |
|----------|-------------|
| [release-secrets.md](release-secrets.md) | Registry of all GitHub Secrets + Edge Function secrets. First-time registration. |
| [vendor-setup.md](vendor-setup.md) | Apple Developer / App Store Connect / Universal Links one-time setup. |
| [i18n-convention.md](i18n-convention.md) | Key-naming rules across the 5 i18n sources. |
| [tdd-workflow.md](tdd-workflow.md) | Test-driven development methodology. |
| [chart-coordinates.md](chart-coordinates.md) | Chart coordinate system reference. |
| [privacy-policy.md](privacy-policy.md) | Privacy policy text source-of-truth. |
| [store-listing.md](store-listing.md) | App Store / Play Store listing copy source-of-truth. |
| [symbol-review/](symbol-review/) | Per-phase symbol design review records. |

## Template / ECC reference

| Document | Description |
|----------|-------------|
| [ecc-overview.md](ecc-overview.md) | What is Everything Claude Code and how it works. |
| [ci-cd-pipeline.md](ci-cd-pipeline.md) | GitHub Actions workflows and automation. |
| [devcontainer.md](devcontainer.md) | Development container setup. |
| [github-features.md](github-features.md) | CODEOWNERS, Dependabot, templates, branch protection. |
| [template-usage.md](template-usage.md) | How to create a project from this template. |
| [adr/000-template.md](adr/000-template.md) | Architecture Decision Record format. |
