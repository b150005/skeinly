# Knit Note — top-level orchestration.
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
	@awk 'BEGIN {FS = ":.*##"; printf "\nKnit Note — make targets\n\n"} \
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

ci-local:  ## Reproduce the CI pre-push invariant chain locally.
	./gradlew \
		:shared:ktlintCheck \
		:shared:compileTestKotlinIosSimulatorArm64 \
		:shared:testAndroidHostTest \
		:shared:koverVerify \
		verifyI18nKeys

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
