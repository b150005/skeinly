# `.well-known` files — Universal Links + App Links deploy templates

This directory holds the **content** for two well-known files that must
be served from the **root domain** for Apple Universal Links + Android
App Links to work:

| File | Purpose | Deploy target |
|---|---|---|
| `apple-app-site-association` | iOS Universal Links AASA | `https://<root-host>/.well-known/apple-app-site-association` |
| `assetlinks.json` | Android App Links | `https://<root-host>/.well-known/assetlinks.json` |

## Why these files are NOT under `docs/public/`

Skeinly's GitHub Pages site is published as **Project Pages** at
`https://b150005.github.io/skeinly/`. Apple + Google only look for
well-known files under the **root domain** path:

- Apple: `https://b150005.github.io/.well-known/apple-app-site-association`
- Google: `https://b150005.github.io/.well-known/assetlinks.json`

Placing the files under `docs/public/.well-known/` would publish them
at `https://b150005.github.io/skeinly/.well-known/...`, which Apple
and Google ignore. So the files live here as a **deploy template** for
whichever target the project chooses below.

## Deploy decision (pending — pre-alpha audit item A16)

Three viable deploy targets. Pick one before alpha launch:

### Option 1 — Custom domain on Skeinly site

Acquire a custom domain (e.g., `skeinly.app`) and point GitHub Pages
at it. Update entitlements + assetlinks accordingly:

- `iosApp/iosApp.entitlements` → `applinks:skeinly.app`
- `AndroidManifest.xml` → host changes to `skeinly.app`
- Pages workflow → publish `.well-known/apple-app-site-association` +
  `assetlinks.json` to the site root.

Pro: clean URLs (`https://skeinly.app/pull-request/abc`). Best long-term
branding. Annual domain fee (~$10–15/year).

### Option 2 — User Pages repo (`b150005.github.io`)

Create / use a separate `b150005.github.io` repository (User Pages, not
Project Pages). Publish the `.well-known/` directory there. Skeinly's
Project Pages site at `b150005.github.io/skeinly/` continues to serve
the privacy policy + ToS + help + account-deletion pages.

Pro: no domain cost. Reuses existing GitHub identity. Con: requires
maintaining a second repository; the User Pages site may need a
minimal landing index that doesn't conflict with other projects.

### Option 3 — External PaaS (Vercel / Netlify / Cloudflare Pages)

Front a small static site on an external PaaS that serves only the
`.well-known/` directory + redirects everything else to GitHub Pages.

Pro: full control over headers + caching. Con: another moving part to
operate; Skeinly is a thin operation, this adds an unnecessary surface.

### Recommendation

**Option 2** for alpha (zero cost, GitHub-native, isolates the
`.well-known/` concern from the marketing site). Re-evaluate Option 1
post-GA when a marketing site at a custom domain becomes worth
acquiring on its own merit.

## Per-file deploy steps (once target decided)

### `apple-app-site-association`

1. Replace `TEAMID_PLACEHOLDER` in this file with the Apple Developer
   Team ID (10-character alphanumeric, e.g., `ABC1234567`). Available
   from <https://developer.apple.com/account> → Membership.
2. Copy the file to the deploy target's `/.well-known/` directory.
3. Serve with `Content-Type: application/json` (Apple is strict about
   the MIME type; some servers default to `application/octet-stream`
   which Apple rejects).
4. **No file extension** — Apple requires the literal filename
   `apple-app-site-association`, not `.json`.
5. Verify via curl:
   ```bash
   curl -I https://<root-host>/.well-known/apple-app-site-association
   # Expected: 200 OK, Content-Type: application/json
   ```
6. Use Apple's [Universal Links validator](https://search.developer.apple.com/appsearch-validation-tool/) to confirm
   the AASA is parseable.
7. Regenerate the Distribution Provisioning Profile so the Associated
   Domains capability is included. The `iosApp.entitlements` already
   declares `applinks:b150005.github.io` — without a matching profile
   the entitlement gets stripped at signing time.

### `assetlinks.json`

The file content already lives in this repository (deployed via the
existing setup that the `release.yml` workflow validates at
`https://b150005.github.io/.well-known/assetlinks.json`). If migrating
to a new root host, replicate the file there with the same SHA-256
fingerprints of the production + debug signing certs. Validate via
<https://developers.google.com/digital-asset-links/tools/generator>.

## Once deployed — wiring is already done

Code-side wiring is complete (pre-alpha A16):

- `iosApp/iosApp.entitlements` declares `applinks:b150005.github.io`
  (will need to change to whichever host Option 1/2/3 selects).
- `AppDelegate.swift` `application(_:continue:restorationHandler:)`
  handles the `NSUserActivityTypeBrowsingWeb` callback and posts the
  same `.openPushRoute` notification used by APNs taps. `AppRootView`'s
  existing `.onReceive(...)` routes via `NavigationPath.append`.
- `extractUniversalLinkRoute(from:)` extracts the host-relative path
  (`pull-request/<id>`) from the inbound `https://` URL, with a base-
  path strip for `/skeinly/` so the same code works whether the deploy
  target is at the root or under Project Pages.

Once the AASA file is deployed + provisioning profile regenerated, the
end-to-end flow works without further code changes.

## Cross-reference

- [pre-alpha-checklist.md §25](../en/ops/pre-alpha-checklist.md) — A16 closure record
- [iosApp/iosApp.entitlements](../../iosApp/iosApp/iosApp.entitlements) — entitlement declaration
- [.github/workflows/release.yml](../../.github/workflows/release.yml) — AASA / assetlinks URL validation step
- [Apple Universal Links docs](https://developer.apple.com/documentation/xcode/supporting-associated-domains)
- [Android App Links docs](https://developer.android.com/training/app-links)
