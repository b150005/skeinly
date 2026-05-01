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
        e2e-android e2e-ios \
        lint format coverage i18n-verify \
        ci-local \
        verify-xcode \
        release-ipa-local \
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

ios-build:  ## Build iOS app (debug, simulator). Requires Xcode 26+ and xcodegen.
	cd iosApp && xcodegen generate
	xcodebuild build \
		-project iosApp/iosApp.xcodeproj \
		-scheme iosApp \
		-sdk iphonesimulator \
		-configuration Debug \
		-destination 'generic/platform=iOS Simulator' \
		CODE_SIGNING_ALLOWED=NO

ios-test:  ## Run iOS XCUITest. Override target sim via IOS_SIM_DEST.
	cd iosApp && xcodegen generate
	xcodebuild test \
		-project iosApp/iosApp.xcodeproj \
		-scheme iosApp \
		-destination '$(IOS_SIM_DEST)' \
		CODE_SIGNING_ALLOWED=NO

##@ End-to-End (Maestro)

e2e-android:  ## Run Android Maestro flows (requires running emulator/device).
	bash e2e/run-android.sh

e2e-ios:  ## Run iOS Maestro flows (requires running simulator).
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
		verifyI18nKeys
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

release-ipa-local: verify-xcode  ## Build a Release IPA locally via fastlane (no TestFlight upload). For pre-tag verification.
	cd iosApp && bundle exec fastlane build_ipa

##@ Maintenance

clean:  ## Remove Gradle and iOS build artifacts.
	./gradlew clean
	rm -rf iosApp/build iosApp/iosApp.xcodeproj iosApp/Pods iosApp/DerivedData
