# Runbook — Release

> **Purpose**: tag a release of Skeinly and ship it to TestFlight + Play Internal Testing.
>
> **Audience**: the operator triggering a release.
>
> **Frequency**: per release (typically every 1–4 weeks during beta; less frequent post-GA).

## Mental model

Releases are **tag-driven**. Pushing a tag matching `v*` on `main` triggers `.github/workflows/release.yml` which runs three jobs in parallel and uploads to both stores. The local toolchain runs the same verification chain as CI (`make ci-local`); there is no daylight between local-green and CI-green.

Versioning today (during closed beta, pre-Phase-40 GA) uses semver `0.X.Y`. The major-zero is load-bearing — `BuildFlags.isBeta` codegen keys off `major == 0`, and the iOS `CFBundleShortVersionString` accepts only digits+dots so hyphen-suffixed prerelease tags would be rejected by App Store Connect.

Sources of truth:
- [version.properties](../../../version.properties) — `VERSION_NAME` (semver string) + `VERSION_CODE` (monotonic int for Play).
- [iosApp/project.yml](../../../iosApp/project.yml) — `MARKETING_VERSION` mirrors `VERSION_NAME`. `CURRENT_PROJECT_VERSION` is overridden by CI to `github.run_number`.
- The Gradle invariant `verifyIosBetaFlag` asserts `(major == 0) <=> (IS_BETA == "YES")` so a one-sided bump cannot land.

## Pre-flight: verify locally

Before the first tag of a new release, exercise the signing chain on your laptop:

```bash
make release-ipa-local   # signed IPA via fastlane, no upload
make release-aab-local   # signed AAB via Gradle, no upload
```

Both run end-to-end against the prod-grade signing materials in your local keychain / `keystore.jks`. A failure here is much cheaper to diagnose than a CI red after tag push.

If you've made any code change since the last release:

```bash
make ci-local
```

This is the **canonical pre-push entry point**. ~30–45 min wall clock; requires a booted Android emulator + iOS Simulator. It reproduces every CI check.

## Step 1: bump the version

Edit [version.properties](../../../version.properties):

```properties
VERSION_NAME=0.1.1
VERSION_CODE=2
```

Rules:
- `VERSION_NAME` is semver. During beta it stays at `0.X.Y`; Phase 40 GA bumps to `1.0.0`.
- `VERSION_CODE` MUST be strictly greater than the most recent successful Play Console upload. Play rejects duplicates and re-using a code is unrecoverable.
- The iOS build number is sourced from `github.run_number` of the Release workflow run — no manual edit needed.

Edit [iosApp/project.yml](../../../iosApp/project.yml):

```yaml
settings:
  base:
    MARKETING_VERSION: 0.1.1   # must match version.properties VERSION_NAME
    IS_BETA: "YES"             # YES while major == 0, NO at v1.0.0
```

Then regenerate the Xcode project:

```bash
make ios-regenerate   # or: cd iosApp && xcodegen
```

Commit:

```bash
git add version.properties iosApp/project.yml
git commit -m "chore(release): bump to v0.1.1"
git push origin main
```

Wait for CI on this commit to go green before tagging.

## Step 2: validate then tag

```bash
make release-tag-validate          # pre-flight: branch=main, clean tree, tag does not exist
CONFIRM=yes make release-tag-publish   # tags v$VERSION_NAME on HEAD and pushes
```

The `CONFIRM=yes` env var is a defense against accidental tag pushes (a stray Tab-completion cannot trigger a production upload). The `release-tag-publish` target derives the tag name from `version.properties` `VERSION_NAME`, so the only edit per release is bumping that file.

If you prefer raw git:

```bash
git tag -a v0.1.1 -m "Release v0.1.1"
git push origin v0.1.1
```

Either path triggers `.github/workflows/release.yml`.

## Step 3: monitor the CI run

```bash
gh run list --workflow=release.yml -L 1
gh run watch <run-id>
```

Three parallel jobs run:

| Job | What it does | Time |
|---|---|---|
| `build-android` | `bundleRelease` → `publishBundle` uploads to Play Internal Testing as `releaseStatus = DRAFT`. APK produced as workflow artifact. | ~8–12 min |
| `build-ios` | fastlane `beta` lane: ephemeral keychain → import cert + profile from secrets → `build_app` → `upload_to_testflight skip_waiting_for_build_processing: true`. | ~12–20 min |
| `create-release` | Draft GitHub Release with IPA + APK attached. | ~1 min after the two builds finish |

The `release-tag-publish` Makefile target gracefully degrades when secrets are missing — each platform's upload step gates on the presence of its required secrets and emits a `::warning::` rather than failing, so a half-configured release still produces an artifact-only build.

If a job fails, see [incident-playbook.md → "Release CI failure"](incident-playbook.md#release-ci-failure).

## Step 4: post-upload manual steps

### Play Console (Android)

1. Open Play Console → Skeinly → Internal Testing track.
2. Find the new Draft release at the top.
3. Fill release notes (en + ja required for the JP store listing).
4. Click "Save → Review release → Start rollout to Internal testing".

The DRAFT state is **load-bearing**: testers don't see the build until you click rollout. This was an intentional choice to prevent CI from silently shipping a regression to testers. See [ADR-006](../adr/006-ci-signing-strategy.md) for the full reasoning.

### App Store Connect (iOS)

1. Wait for Apple processing. Typically 5–30 min; occasionally longer.
2. On the **first build of a new app version**, ASC prompts for export-compliance disclosure. Skeinly uses standard OS-provided HTTPS-only encryption and qualifies for the exemption — answer "No" to the "uses non-exempt encryption" question.
3. Once processing finishes, add the build to your Internal Testing group.

External (public-link) TestFlight tracks require Apple's review (~24h on first build of a version, faster on subsequent builds in the same train). Plan accordingly.

## Step 5: smoke test from a real device

For each platform, after the build lands on a tester device:

- Cold-launch the app → onboarding (if first install) → land on home.
- Open the chart editor → verify the symbol palette populates (this exercises the symbol pack sync path).
- Trigger one collaboration event (open a Suggestion or post a comment) on a build that has push notifications wired — verify the push lands on the destination device.
- Submit a bug report from Settings → Send Feedback (beta gestures: 3-finger long press on Android, shake on iOS) — verify the Issue lands on `b150005/skeinly`.

If any smoke test fails, the release is **not done**. Either:
- Fix forward via a new patch (`v0.1.2`).
- (Last resort) Pull the build from the testing track (Play Console: "Halt rollout"; ASC: expire the build for testers). Then fix forward.

## Step 6: post-release housekeeping

- Update [CLAUDE.md](../../../.claude/CLAUDE.md) `### Completed` section with the new release entry (commit + post-release).
- If this was a Phase-closing release, promote the phase entry from `### Completed` to `docs/en/phase/completed-archive.md` per the archive policy.
- If this release bumped `0.X.Y` → `1.0.0` (Phase 40 GA), also flip `iosApp/project.yml` `IS_BETA: "YES"` → `"NO"` (the `verifyIosBetaFlag` task enforces this coupling — a one-sided commit fails CI immediately).

## Troubleshooting

See [incident-playbook.md](incident-playbook.md) for symptom-indexed failure modes.

Common gotchas:

- **`version.properties` bumped but `iosApp/project.yml` not regenerated**: the `verifyIosBetaFlag` check fails at CI. Fix by running `make ios-regenerate` and re-committing.
- **Play Console rejects upload with "Version code already used"**: bump `VERSION_CODE` and re-tag with the same `VERSION_NAME` + the higher code. Cannot recover an in-use code.
- **fastlane fails with "No provisioning profile found"**: a secret rotated without re-registering. See [secrets-rotation.md](secrets-rotation.md) for the profile + cert chain.
- **`upload_to_testflight` succeeds but the build never appears in ASC**: Apple sometimes silently drops uploads with metadata mismatches. Check `xcrun altool --validate-app` output in the workflow log; the validation error reveals the mismatch (usually a bundle id or entitlement disagreement after a vendor-setup change).

## Reference

- [.github/workflows/release.yml](../../../.github/workflows/release.yml) — the workflow itself.
- [docs/en/release-secrets.md](../release-secrets.md) — every secret the workflow reads.
- [docs/en/vendor-setup.md](../vendor-setup.md) — Apple Developer + ASC + Universal Links one-time setup.
- [docs/en/ops/repo-policy.md](repo-policy.md) — branch protection that gates `main`.
- [ADR-006](../adr/006-ci-signing-strategy.md) — code signing strategy.
