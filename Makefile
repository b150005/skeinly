# Skeinly — top-level orchestration.
#
# Run `make help` to see the full target list with descriptions.
# Targets are thin wrappers over Gradle / xcodebuild / fastlane / Maestro;
# heavy logic lives in those tools, not in Makefile recipes.

# Default iOS Simulator destination for `make ios-test`. Override on the
# command line, e.g. `IOS_SIM_DEST='platform=iOS Simulator,name=iPad Pro 13' make ios-test`.
IOS_SIM_DEST ?= platform=iOS Simulator,name=iPhone 16

.PHONY: help \
        setup \
        shared-test \
        android-build android-test android-install \
        ios-build ios-test \
        .ensure-local-xcconfig \
        e2e-android e2e-ios \
        lint format coverage i18n-verify \
        ci-local \
        verify-xcode \
        release-ipa-local release-aab-local \
        release-tag-validate release-tag-publish \
        clean

help:  ## Show this help.
	@awk 'BEGIN {FS = ":.*##"; printf "\nSkeinly — make targets\n\n"} \
		/^[a-zA-Z][a-zA-Z0-9_-]*:.*##/ { printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2 } \
		/^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) }' $(MAKEFILE_LIST)
	@echo

##@ Setup

setup:  ## Install fastlane gems + remind about other tooling.
	cd iosApp && bundle install
	@echo
	@echo "Other tools to install (if not already present):"
	@echo "  brew install xcodegen maestro"
	@echo "  Android SDK + platform-tools (adb) via Android Studio"
	@echo "  Xcode 26+ from the App Store (required by Apple App Store Connect since 2026-04-28)"
	@echo "  macOS 26.0+ (Tahoe) on the host"

##@ Build & Test — Shared (KMP)

shared-test:  ## Run shared module tests across all targets (commonTest + androidHostTest + iosSimulatorArm64Test).
	./gradlew :shared:allTests

##@ Build & Test — Android

android-build:  ## Build Android debug APK.
	./gradlew :androidApp:assembleDebug

android-test:  ## Run Android unit tests.
	./gradlew :androidApp:testDebugUnitTest

android-install:  ## Install debug APK on the connected device or emulator.
	./gradlew :androidApp:installDebug

##@ Build & Test — iOS

# Auto-bootstrap iosApp/local.xcconfig from the committed example so
# `xcodegen generate` finds the file referenced by `configFiles:` in
# project.yml (Debug + Release both point at local.xcconfig).
#
#   - If iosApp/local.xcconfig already exists, this target is a no-op.
#     Never overwrites a developer's customizations.
#   - If absent: copy from local.xcconfig.example unchanged. The user
#     edits DEVELOPMENT_TEAM (and any other settings — SUPABASE_URL,
#     SENTRY_DSN_IOS, etc.) once after the first `make ios-build` and
#     it stays in place for all subsequent runs. Once project.yml stopped
#     overriding DEVELOPMENT_TEAM (the same commit that simplified this
#     target), the value in local.xcconfig flows through to the .xcodeproj
#     and Xcode UI shows the Team selected.
#
# A previous iteration of this target offered an `APPLE_DEVELOPMENT_TEAM_ID`
# env var path for zero-config bootstrap; it was removed because per-user
# shell env vars are project-leaky (other Xcode projects on the same
# machine would pick up the same variable). Editing iosApp/local.xcconfig
# directly keeps the configuration project-scoped.
#
# CI never reaches this target — workflows (ci.yml, e2e.yml, security.yml,
# release.yml) write iosApp/local.xcconfig with explicit values and call
# xcodebuild directly, not via `make ios-*`. So this prereq is strictly a
# local DX improvement.
.ensure-local-xcconfig:
	@if [ ! -f iosApp/local.xcconfig ]; then \
		cp iosApp/local.xcconfig.example iosApp/local.xcconfig; \
		echo "[ios-setup] Created iosApp/local.xcconfig from local.xcconfig.example."; \
		echo "[ios-setup]   Open iosApp/local.xcconfig and replace YOUR_TEAM_ID_HERE with your 10-char Apple Developer Team ID."; \
		echo "[ios-setup]   Find your Team ID at: Apple Developer portal -> Membership -> Team ID."; \
		echo "[ios-setup]   Other settings (SUPABASE_URL, SENTRY_DSN_IOS, REVENUECAT_API_KEY, etc.) are optional — leave empty for local-only mode."; \
		echo "[ios-setup]   After editing, re-run 'make ios-build'."; \
	fi

ios-build: .ensure-local-xcconfig  ## Build iOS app (debug, simulator). Requires Xcode 26+ and xcodegen.
	cd iosApp && xcodegen generate
	xcodebuild build \
		-project iosApp/iosApp.xcodeproj \
		-scheme iosApp \
		-sdk iphonesimulator \
		-configuration Debug \
		-destination 'generic/platform=iOS Simulator' \
		CODE_SIGNING_ALLOWED=NO

ios-test: .ensure-local-xcconfig  ## Run iOS XCUITest. Override target sim via IOS_SIM_DEST.
	cd iosApp && xcodegen generate
	xcodebuild test \
		-project iosApp/iosApp.xcodeproj \
		-scheme iosApp \
		-destination '$(IOS_SIM_DEST)' \
		CODE_SIGNING_ALLOWED=NO

##@ End-to-End (Maestro)

# Pre-flight cleanup target — invoked before every e2e run to defend against
# zombie state from prior sessions:
#   1. `maestro test` processes left over from killed runs (port 7001 holders).
#   2. `maestro mcp` daemon processes older than 4h — these come from prior
#      Claude Code MCP integration sessions that didn't shut down cleanly.
#      Recent (<4h) `maestro mcp` processes are LEFT ALONE so an active MCP
#      integration in another window/session is not disrupted.
#   3. adb daemon — restarted to clear stale port forwards (Maestro driver
#      uses tcp:7001 forward and stale forwards cause UNAVAILABLE gRPC errors).
#
# Scope guarantees:
#   - All `pkill` / `kill` calls are scoped to `-u $$USER` so other users on
#     the same machine are never affected.
#   - The 4h `etimes` threshold (14400 seconds) is generous — typical e2e runs
#     finish in <30 minutes, so anything still running at 4h is reliably stale.
#
# Platform note: `ps -o etimes=` is BSD-syntax (macOS). This target is invoked
# only as a dependency of `e2e-android` / `e2e-ios`, both of which require a
# booted Android emulator + iOS Simulator and therefore run only on macOS dev
# hosts. CI runs `make e2e-android` / `make e2e-ios` on `macos-latest` GitHub
# Actions runners, which are also BSD `ps`. Linux portability is not required.
# If a future Linux-host need surfaces, replace with `pgrep -u "$$USER" -f
# "maestro" | xargs ps -o etimes= -p 2>/dev/null` (POSIX-portable form).
e2e-clean:  ## Clean up zombie maestro/adb state before an e2e run.
	@echo "[e2e-clean] Killing leftover 'maestro test' processes..."
	@pkill -u $$USER -9 -f "maestro\\.cli\\.AppKt test" 2>/dev/null || true
	@echo "[e2e-clean] Killing 'maestro mcp' processes older than 4h (preserving recent MCP integrations)..."
	@ps -u $$USER -o pid=,etimes=,command= | awk '/maestro\\.cli\\.AppKt mcp/ && $$2 > 14400 { print $$1 }' | xargs -r kill -9 2>/dev/null || true
	@echo "[e2e-clean] Restarting adb to clear stale port forwards..."
	@adb kill-server >/dev/null 2>&1 || true
	@adb start-server >/dev/null 2>&1 || true
	@echo "[e2e-clean] Done."

e2e-android: e2e-clean  ## Run Android Maestro flows (requires running emulator/device).
	bash e2e/run-android.sh

e2e-ios: e2e-clean  ## Run iOS Maestro flows (requires running simulator).
	bash e2e/run-ios.sh

##@ Quality Gate

lint:  ## Run ktlint check across all source sets.
	./gradlew ktlintCheck

format:  ## Auto-fix ktlint violations.
	./gradlew ktlintFormat

coverage:  ## Generate Kover coverage XML and verify the 80% threshold.
	./gradlew :shared:koverXmlReport :shared:koverVerify

i18n-verify:  ## Verify i18n key parity across androidApp, shared composeResources, and iOS xcstrings.
	./gradlew verifyI18nKeys

# Comprehensive pre-push verification — reproduces every check that
# CI runs, locally, in the order CI fails fastest. Run BEFORE every
# `git commit` (or, at minimum, before every `git push`).
#
# Time cost: ~30-45 minutes. Requires booted iOS Simulator + Android
# emulator (or connected Android device) before invocation —
# `ios-test`, `e2e-android`, and `e2e-ios` all need them and will fail
# fast with a clear error message if missing.
#
# Why slow: Phase 39.4 added a 4th onboarding consent page, which
# silently broke the Android Maestro suite (9/9 flows) and the iOS
# `OnboardingUITests.testTappingNextAdvancesThroughAllPagesAndCompletes`
# XCUITest. The prior fast (~3 min) ci-local + verify-ios chain was
# compile-only on the iOS side and skipped Maestro entirely, so
# neither runtime regression was caught locally before push. The full
# chain below pays the time cost in exchange for catching anything CI
# would catch — there is no longer a gap between "make ci-local
# green" and "CI green".
#
# Layered reasoning:
#   1. Cheapest: Gradle KMP + Android module (~5-7 min). Catches
#      ktlint, type errors, JVM unit tests, coverage, i18n parity.
#   2. iOS app build (~30s). Catches Swift compile errors + linker
#      issues with the `generic/platform=iOS Simulator` destination
#      (multi-arch — both arm64 and x86_64), which the specific-sim
#      `ios-test` destination (arm64-only on Apple Silicon) does not.
#   3. iOS XCUITest (~5-7 min). `xcodebuild test` internally does
#      `build-for-testing` then runs every test. Catches
#      `Core/Bridging/` test-target compile regressions (the prior
#      reason `verify-ios` existed) AND runtime XCUITest assertion
#      failures (which `xcodebuild build-for-testing` could not).
#   4. Android Maestro E2E (~20 min). `bash e2e/run-android.sh`
#      builds debug APK + verifies running emulator + installs +
#      runs every flow under `e2e/flows/android/` (excluding
#      `requires-supabase`).
#   5. iOS Maestro E2E (~15 min). `bash e2e/run-ios.sh` similar,
#      excluding `skip-ios26` + `requires-supabase`.
ci-local:  ## Comprehensive pre-push verification — reproduces every CI check (~30-45 min, requires booted emu+sim).
	./gradlew \
		:shared:ktlintCheck \
		:androidApp:ktlintCheck \
		:shared:compileTestKotlinIosSimulatorArm64 \
		:shared:testAndroidHostTest \
		:shared:koverVerify \
		verifyI18nKeys \
		verifyIosBetaFlag
	$(MAKE) ios-build
	$(MAKE) ios-test
	$(MAKE) e2e-android
	$(MAKE) e2e-ios

##@ Release

verify-xcode:  ## Verify Xcode 26+ is installed (Apple App Store Connect requirement since 2026-04-28).
	@xcodebuild -version 2>/dev/null | head -n 1 | \
		awk '{ split($$2, v, "."); if (v[1]+0 < 26) { \
			printf "ERROR: Xcode 26+ required (Apple App Store requirement since 2026-04-28); found: %s\n", $$0; \
			exit 1 } else { printf "OK: %s\n", $$0 } }'

release-ipa-local: verify-xcode .ensure-local-xcconfig  ## Build a Release IPA locally via fastlane (no TestFlight upload). For pre-tag verification.
	cd iosApp && bundle exec fastlane build_ipa

release-aab-local:  ## Build a Release AAB locally (no Play upload). For pre-tag verification of the Android signing chain.
	./gradlew :androidApp:bundleRelease
	@echo ""
	@echo "AAB produced at:"
	@find androidApp/build/outputs/bundle/release -name "*.aab" -print
	@echo ""
	@echo "To publish to Internal track manually:"
	@echo "  export ANDROID_PUBLISHER_CREDENTIALS=\"\$$(cat ~/path/to/google-play-publisher-sa.json)\""
	@echo "  ./gradlew :androidApp:publishBundle"

# Tag-driven release workflow.
#
# `release.yml` is wired to the `v*` tag pattern; pushing a tag like
# `v0.1.0` triggers the full Release workflow (Android AAB build →
# Play Internal track upload, iOS archive → TestFlight upload, GitHub
# Release with IPA + APK artifacts attached). Secrets live only in CI;
# tag push is the single user-facing entry point.
#
# `release-tag-validate` runs pre-flight checks WITHOUT side effects so
# the user can verify branch / working-tree / tag uniqueness state before
# committing to a push. `release-tag-publish` re-runs the same checks
# then performs the tag + push, gated behind an explicit CONFIRM=yes
# env var so a stray Tab-completion can't trigger a production upload.
#
# Tag derivation: `v$(VERSION_NAME)` from version.properties. Bump
# version.properties + commit + push BEFORE running this. Tag re-use is
# explicitly rejected (Play Console + App Store Connect both reject
# duplicate version codes / build numbers from the server side too, but
# we want the failure to surface locally before the push).

release-tag-validate:  ## Pre-flight check for tag push (branch=main, clean tree, tag does not exist). No side effects.
	@VERSION="$$(grep '^VERSION_NAME=' version.properties | cut -d= -f2)"; \
	if [ -z "$$VERSION" ]; then \
		echo "ERROR: could not read VERSION_NAME from version.properties"; exit 1; \
	fi; \
	TAG="v$$VERSION"; \
	BRANCH="$$(git rev-parse --abbrev-ref HEAD)"; \
	if [ "$$BRANCH" != "main" ]; then \
		echo "ERROR: must be on main; currently on '$$BRANCH'"; exit 1; \
	fi; \
	if [ -n "$$(git status --porcelain)" ]; then \
		echo "ERROR: working tree dirty — commit or stash before tagging"; \
		git status --short; exit 1; \
	fi; \
	if git rev-parse "$$TAG" >/dev/null 2>&1; then \
		echo "ERROR: tag $$TAG already exists locally — bump VERSION_NAME first"; exit 1; \
	fi; \
	if git ls-remote --tags origin "$$TAG" 2>/dev/null | grep -q "refs/tags/$$TAG$$"; then \
		echo "ERROR: tag $$TAG already exists on origin — bump VERSION_NAME first"; exit 1; \
	fi; \
	echo "OK: ready to tag $$(git rev-parse --short HEAD) on main as $$TAG"; \
	echo "    (run 'CONFIRM=yes make release-tag-publish' to push)"

release-tag-publish: release-tag-validate  ## Tag the current commit and push to trigger Release workflow. Requires CONFIRM=yes.
	@VERSION="$$(grep '^VERSION_NAME=' version.properties | cut -d= -f2)"; \
	TAG="v$$VERSION"; \
	echo ""; \
	echo "About to tag $$(git rev-parse --short HEAD) on main as $$TAG and push to origin."; \
	echo "This triggers the Release workflow:"; \
	echo "  - Android AAB build + signed upload to Play Console Internal Testing track"; \
	echo "  - iOS archive + signed upload to TestFlight (waits for Apple processing async)"; \
	echo "  - GitHub Release (draft) with IPA + APK artifacts attached"; \
	echo ""; \
	if [ "$$CONFIRM" != "yes" ]; then \
		echo "ERROR: re-run with CONFIRM=yes to proceed:"; \
		echo "  CONFIRM=yes make release-tag-publish"; \
		exit 1; \
	fi; \
	git tag -a "$$TAG" -m "Release $$TAG"; \
	git push origin "$$TAG"; \
	echo ""; \
	echo "Tag $$TAG pushed. Watch the workflow:"; \
	echo "  gh run watch --repo b150005/skeinly --workflow=release.yml"; \
	echo ""; \
	echo "Or list runs:"; \
	echo "  gh run list --repo b150005/skeinly --workflow=release.yml --limit 3"

##@ Maintenance

clean:  ## Remove Gradle and iOS build artifacts.
	./gradlew clean
	rm -rf iosApp/build iosApp/iosApp.xcodeproj iosApp/Pods iosApp/DerivedData
