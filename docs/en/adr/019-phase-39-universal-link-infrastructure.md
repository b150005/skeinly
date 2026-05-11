# ADR-019 — Phase 39 (W3): Universal Link / Android App Link Infrastructure

> **Status**: Accepted (2026-05-11)
> **Phase**: 39 (W3 wave — alpha-launch prerequisites)
> **Supersedes**: none
> **Superseded by**: none
> **Related**: ADR-017 §3.8 (push deep-link routing — uses host-relative paths, sibling format to the Universal Link URL family decided here).

JA summary: [../../ja/adr/019-phase-39-universal-link-infrastructure.md](../../ja/adr/019-phase-39-universal-link-infrastructure.md) — see also [ja/adr/019-phase-39-universal-link-infrastructure.md] for the Japanese-language mirror.

## 1. Context

Phase 39 (W3) introduces external Universal Link (iOS) / Android App Link support so users can share Skeinly content via SMS, iMessage, email, and Slack and have links open the app directly. The pre-W3 implementation used a custom URL scheme `skeinly://share/<token>` ([Phase 33 work, removed in W3 commit 5847508](../../../androidApp/src/main/AndroidManifest.xml)), which:

1. Did not unfurl as a rich preview in chat apps.
2. Showed no web-fallback when the recipient did not have Skeinly installed.
3. Could not benefit from domain ownership verification (any app could claim the `skeinly://` scheme).

The W3 deep-link audit (2026-05-10, conducted via agent-team deliberation including product-manager, architect, ui-ux-designer, knitter, and security-reviewer voices) settled the URL family as `https://b150005.github.io/skeinly/<resource>/<id>` for three resource types: `patterns/shared/<token>` (token-gated pattern share), `patterns/<patternId>` (public pattern detail — alpha-scope placeholder for post-alpha public discovery), and `pull-requests/<prId>` (PR detail). All other public URL surfaces (Discovery, Profile, Variation, Suggestion list) are deferred to post-alpha.

The remaining open question after the W3 commit was **where to host the Apple App Site Association (AASA) and Android assetlinks.json files**. Both Apple and Google fetch these files from a fixed location at the **root** of the apex domain:

- iOS: `https://<domain>/.well-known/apple-app-site-association`
- Android: `https://<domain>/.well-known/assetlinks.json`

Neither platform falls back to a Project Pages subpath. Skeinly's Project Pages site at `https://b150005.github.io/skeinly/` therefore cannot host these files — placing them at `b150005.github.io/skeinly/.well-known/...` resulted in the W3 first attempt being structurally broken (pages CD detected the 404 on commit 5847508 and failed — the right outcome).

Two viable hosting options surfaced:

| | Option A: Custom domain (e.g. `skeinly.app`) | Option B: User Pages repo `b150005/b150005.github.io` |
|---|---|---|
| Domain branding | `skeinly.app/patterns/shared/<token>` (clean, branded) | `b150005.github.io/skeinly/patterns/shared/<token>` (long, personal-username-flavored) |
| Cost | ~$15/year domain registration | Free |
| Setup | DNS configuration + GitHub Pages custom-domain mapping + Skeinly app URL family change | Create User Pages repo (1-click) + push two files |
| Future migration | None needed | Migrate to custom domain at GA — small commit changing host in entitlements + Manifest + parser |

## 2. Decisions

### 2.1 Hosting: User Pages repo (Option B) for alpha; revisit Option A at GA

**Rationale:**

- **Speed-to-alpha**: Option B is one repo creation + one push; Option A requires domain purchase + DNS propagation + Pages reconfiguration + Skeinly-side host change. Alpha launch should not be gated on a domain decision.
- **Structurally equivalent**: Both options unblock Universal Link / App Link verification. Apple AASA and Google assetlinks.json behave identically regardless of which domain hosts them, provided the apex-domain root rule is met.
- **Cheap migration path**: If we adopt Option A at GA, the changes are bounded: (a) update `iosApp/iosApp.entitlements` `applinks:` value, (b) update `androidApp/src/main/AndroidManifest.xml` intent-filter `host=` attribute, (c) update `parseExternalRoute` / `parseExternalRoute(url:)` (Kotlin + Swift) expected hostname, (d) update [share URL generation](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/projectdetail/ShareLinkDialog.kt) to the new host, (e) move AASA + assetlinks files from the User Pages repo to the custom-domain hosting (or keep them in both, which is harmless during migration). No data migration; the only artifact rotation is the AASA + assetlinks files at the new domain.
- **Operational footprint**: Free + GitHub-native means no third-party domain registrar, no DNS-management surface, no recurring billing. For an alpha targeting ≤10 testers, this aligns with the "minimal new third-party surface for the alpha" theme.

### 2.2 File-hosting layout in the User Pages repo

Both files live at the repo root in a `.well-known/` directory:

```
b150005/b150005.github.io
├── .nojekyll
└── .well-known/
    ├── apple-app-site-association
    └── assetlinks.json
```

**Why `.nojekyll`:** GitHub Pages defaults to Jekyll preprocessing on the build, which excludes dot-prefixed directories like `.well-known/` from the deployed output. Adding an empty `.nojekyll` file at the repo root [bypasses Jekyll entirely](https://github.blog/news-insights/the-library/bypassing-jekyll-on-github-pages/) and ships the directory tree as-is. Without this, the AASA endpoint would silently 404.

**Content shape:**

- `apple-app-site-association` (no extension; Apple's convention): declares `appIDs: ["L9ZR4679P5.io.github.b150005.skeinly"]` and `components` for the three resource path patterns. Mime type `application/octet-stream` is what GitHub Pages serves for the no-extension file; [Apple's documentation explicitly accepts this](https://developer.apple.com/documentation/xcode/supporting-associated-domains) ("the content type doesn't have to be `application/json`").
- `assetlinks.json` (extension required by Google): declares `delegate_permission/common.handle_all_urls` for `io.github.b150005.skeinly` with the release keystore's SHA256 fingerprint (`18:B3:0D:4F:...:8C`, alias `knit-note`). Mime type `application/json` is required by Google and is what GitHub Pages serves for `.json` files automatically.

### 2.3 Skeinly URL family (unchanged from the W3 audit)

| Resource | URL pattern | Typed route |
|---|---|---|
| Share token | `https://b150005.github.io/skeinly/patterns/shared/<token>` | `SharedContent(token, shareId=null)` (Compose) / `.sharedContent(token:shareId:)` (SwiftUI) |
| Public pattern | `https://b150005.github.io/skeinly/patterns/<patternId>` | Reserved; alpha-scope placeholder (see §4) |
| Pull request | `https://b150005.github.io/skeinly/pull-requests/<prId>` | `SuggestionDetail(prId)` (Compose) / `.pullRequestDetail(prId:)` (SwiftUI) |

UUID v4 shape required for share tokens (validated in `parseExternalRoute` Kotlin + Swift mirrors, mirroring [`DeepLinkValidator.swift`](../../../iosApp/iosApp/Core/DeepLinkValidator.swift)). Query string and fragment are stripped before route matching so URL tracking parameters and anchor fragments cannot disrupt identifier extraction.

The legacy `skeinly://share/<token>` custom scheme is **fully deleted** (no Tech Debt fallback) per the pre-v1 breaking-changes-accepted policy — alpha has not shipped, no installed clients depend on the old URL shape.

### 2.4 Release-time verification gate

The `validate-tag` job in `.github/workflows/release.yml` performs a pre-build verification of both files at the User Pages domain before downstream Android / iOS Release jobs run:

- HTTP 200 on both AASA + assetlinks endpoints.
- Expected `appID` string present in AASA.
- Expected `package_name` + SHA256 prefix present in assetlinks.

If either check fails, the tag's release is aborted before consuming ~25 minutes of Android + iOS CI time. This catches a "User Pages repo was accidentally cleaned up" failure mode at the earliest possible point.

The same checks form the basis of a future periodic external monitoring job (post-alpha) that pings the endpoints daily so a silent breakage is caught before testers hit it on device.

## 3. Trade-offs and risks

### 3.1 Domain branding (Option B compromise)

The `b150005.github.io/skeinly/...` URL surfaces the maintainer's personal GitHub username in every shared link. For an alpha with 5–10 testers all of whom are aware of the maintainer, this is acceptable. For a beta or GA targeting public users, the link's professionalism matters more — Option A becomes a near-mandatory upgrade. The migration path from Option B to A is bounded (see §2.1) so deferring the decision is low-cost.

### 3.2 Coupling to User Pages repo lifecycle

The Skeinly app's deep-link behavior now depends on a separate repo's deploy health. A misconfiguration on `b150005/b150005.github.io` (accidental file deletion, branch reset, Pages source-branch misconfiguration) would silently break Universal Link verification on next iOS install but **would not** crash existing installations — iOS caches AASA results and Android caches assetlinks verification per install. New installs would experience the link-falls-back-to-browser failure mode.

The release-time verification gate (§2.4) mitigates by failing fast on tag push. A post-alpha monitor (daily curl + alert) closes the gap between releases.

### 3.3 Apple AASA file size limit (128 KB)

AASA must be under 128 KB. Our current file is ~422 bytes — three orders of magnitude under the limit. The limit becomes relevant only if the `components` array balloons to hundreds of distinct path patterns; the alpha scope of three patterns is far from this. Documenting the limit here so a future "add 50 more deep link URLs" change can be sanity-checked.

### 3.4 Android digital asset links cache TTL

Android caches assetlinks.json verification at app-install time. After install, changes to the file (e.g. a future SHA256 update) require app reinstall on existing devices for verification to refresh. iOS AASA cache is similarly aggressive (typically 1 hour to 1 day, exact TTL is unpublished).

For Skeinly, the SHA256 in assetlinks.json should change only if the Play Console signing keystore changes, which is functionally immutable for the lifetime of the app — see [`docs/en/release-secrets.md` §8](../release-secrets.md). Apple TEAM_ID is similarly stable. Cache TTL is a non-issue under normal operation.

## 4. Scope cuts (alpha-only)

The W3 audit identified additional public URL surfaces (Discovery search results, Profile, Variation list, Suggestion list, Chart detail, Project detail). All are deferred to post-alpha — alpha rubric calls for share + PR detail as the only collaboration moments that need cross-app surfaces, and the additional routes have not been requested by any beta-rubric scenario.

The `patterns/<patternId>` (public pattern detail without share token) route is registered in AASA `components` but **does not yet have a typed Compose Navigation destination**. Adding it post-alpha is a one-screen addition (`PatternDetail` Composable + Swift mirror + AppRouter case). Until then, taps on such URLs degrade to web-fallback (which is `b150005.github.io/skeinly/...` — Project Pages site, currently 404 at that subpath; post-alpha we can add a Discovery-style HTML landing page).

Web fallback HTML at `docs/public/skeinly/patterns/shared/index.html` (and similar) is similarly deferred. The Skeinly Project Pages site is where these would live (the User Pages repo only hosts the bare AASA + assetlinks files, no UX).

## 5. Operational runbook

### 5.1 Updating AASA or assetlinks

```bash
git clone https://github.com/b150005/b150005.github.io
cd b150005.github.io
# Edit .well-known/apple-app-site-association or .well-known/assetlinks.json
git add . && git commit -m "..." && git push
# Pages auto-deploys; verify ~30s later via:
curl -fsSL https://b150005.github.io/.well-known/apple-app-site-association
curl -fsSL https://b150005.github.io/.well-known/assetlinks.json
```

### 5.2 Adding a new resource path

1. Decide the URL shape per the §2.3 conventions (resource path + identifier segment).
2. Add the typed Compose Navigation route in `shared/.../NavGraph.kt` if not already present.
3. Extend `parseExternalRoute` (commonMain) + Swift mirror `parseExternalRoute(url:)` (in `iosApp/iosApp/Navigation/AppRouter.swift`) to recognize the new path.
4. Add the matching `components` entry to `.well-known/apple-app-site-association` in the User Pages repo.
5. Push both changes (Skeinly + User Pages) and bump the release tag.

### 5.3 Migration to custom domain (post-alpha Option A path)

When the team decides to upgrade to a branded domain, the steps in order:

1. Register the chosen domain (e.g. `skeinly.app`) via Cloudflare Registrar, Namecheap, or similar.
2. DNS: `A` records pointing to GitHub Pages IPs (185.199.108.153, 185.199.109.153, 185.199.110.153, 185.199.111.153). For `www.` subdomain or apex with CNAME-flattening: CNAME → `b150005.github.io`.
3. GitHub Pages settings on `b150005/b150005.github.io` repo: set custom domain to the chosen domain. The repo's `CNAME` file is auto-created with the domain.
4. Move `.well-known/*` to the new domain by leaving them in the User Pages repo (which is now served at the custom domain).
5. Update the Skeinly repo:
   - `iosApp/iosApp/iosApp.entitlements`: `applinks:b150005.github.io` → `applinks:<new-domain>`.
   - `androidApp/src/main/AndroidManifest.xml`: intent-filter `host=` and AASA `appIDs` unchanged (AASA does not encode the domain — only path).
   - `shared/.../NavGraph.kt`: `expectedPrefix` value in `parseExternalRoute`.
   - `iosApp/iosApp/Navigation/AppRouter.swift`: `expectedHost` in `parseExternalRoute(url:)`.
   - `shared/.../ShareLinkDialog.kt`: share URL generation.
   - `iosApp/iosApp/Screens/ProjectDetailScreen.swift`: same.
6. `.github/workflows/release.yml`: update `AASA_URL` and `ASSETLINKS_URL` constants.
7. Bump release tag; CI validates everything end-to-end.

The migration is roughly one focused day of work plus the time-to-DNS-propagation (usually <1 hour).

## 6. Open questions for post-alpha

- **Daily AASA / assetlinks monitor**: A scheduled GitHub Actions job that pings both endpoints + posts to a Slack / email if either returns non-200. Not required for alpha (manual tag-push verification catches the same scenarios); recommended at beta.
- **Web fallback landing pages**: When a user without the Skeinly app installed taps a `b150005.github.io/skeinly/patterns/shared/<token>` URL, they currently see a 404 from the Skeinly Project Pages site (which doesn't host that path). A small HTML landing page at that path (Apple Smart App Banner + App Store / Play Store buttons) would convert "I saw a Skeinly link" into "I installed the app." Post-alpha polish; alpha testers are pre-instructed to install before tapping links.
- **Universal Link analytics**: PostHog could record `link_opened_via_universal_link` events to measure share-link → app-launch conversion. Out of alpha scope.
