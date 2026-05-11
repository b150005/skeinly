# Operator Runbooks

> Step-by-step procedures for operating Skeinly. Each file in this directory is **self-contained for one task** so you don't need to flip between docs during execution.
>
> For "what does the system look like right now" see [architecture.md](../architecture.md) + [spec/](../spec/). For "why was it built this way" see [adr/](../adr/).

## Index

### Content operations

| Runbook | When to use |
|---|---|
| [content-publishing.md](content-publishing.md) | Publishing a new symbol pack, patching an existing pack, rolling back. Free + Pro tiers. |

### Release operations

| Runbook | When to use |
|---|---|
| [release.md](release.md) | Tag-driven release procedure — versioning, validation, what happens on tag push, post-upload manual steps. |
| [beta-testing.md](beta-testing.md) | Inviting a closed-beta tester (TestFlight + Play Internal + RevenueCat sandbox configuration). |

### Incident response

| Runbook | When to use |
|---|---|
| [incident-playbook.md](incident-playbook.md) | Common failure modes by symptom + first-line triage steps. Symbol pack download, push notification, bug report submission, auth, RevenueCat. |

### Secret / credential operations

| Runbook | When to use |
|---|---|
| [../release-secrets.md](../release-secrets.md) | Registry of all 21 GitHub Secrets + 7 Edge Function secrets. First-time setup walkthrough. |
| [secrets-rotation.md](secrets-rotation.md) | Rotation procedure per secret. Annual hygiene or on suspected leak. |

### Infrastructure

| Runbook | When to use |
|---|---|
| [webhooks.md](webhooks.md) | Supabase Database Webhook configuration (3 webhooks driving `notify-on-write`). |
| [repo-policy.md](repo-policy.md) | Branch protection rules, bypass mechanics, status check gates. |

## Conventions for this directory

- **Self-contained per task** — a runbook covers one task end-to-end without forcing a doc-hop.
- **Commands kept current** — when feature code changes break a runbook command, fix the runbook in the same commit.
- **Link vendor docs, don't restate them** — when a step's authoritative source is an external doc (Supabase, Apple Developer, Google Play, GitHub Apps API), link out instead of restating. Restating creates tech debt as the vendor doc evolves.
- **JA mirror policy** — every ops/ runbook has a JA mirror at `docs/ja/ops/<same-name>.md`. The English version is the source of truth; JA tracks it.

## When to add a new runbook

Add a file in this directory when:
- A new recurring operational task emerges (e.g. "renew the GitHub App private key annually") that isn't covered by an existing runbook.
- An incident response converges on a documented triage path worth preserving.
- A vendor surface change requires a coordinated multi-step procedure.

Don't add a runbook for:
- One-off setup tasks. Add to [vendor-setup.md](../vendor-setup.md) instead.
- Code-level conventions. Add to a `spec/` doc or a rule under `~/.claude/rules/` instead.
