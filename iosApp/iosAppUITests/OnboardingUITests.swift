import XCTest

/// Regression tests for the onboarding flow. These guard against the
/// SwiftUI struct re-init bug where a Koin-resolved ViewModel was created
/// fresh on every View init, causing button taps to dispatch events to an
/// orphan ViewModel while the observer stayed bound to the original state flow.
/// The ScopedViewModel holder fix pins the ViewModel via @StateObject so
/// these flows complete deterministically.
///
/// Page asserts use the locale-independent `onboardingPage{1,2,3}` accessibility
/// identifiers so the tests remain valid under any localization of the
/// xcstrings catalog (Phase 33.1.4 rule: any literal migrated to i18n must
/// be re-anchored to a testTag / accessibilityIdentifier).
final class OnboardingUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app = XCUIApplication()
        // Reset onboarding completion state so the carousel appears on launch.
        app.launchArguments = ["--reset-database", "-has_seen_onboarding", "false"]
    }

    func testTappingNextAdvancesThroughAllPagesAndCompletes() {
        app.launch()

        XCTAssertTrue(
            app.otherElements["onboardingPage1"].waitForExistence(timeout: 5),
            "Onboarding page 1 did not appear"
        )

        let nextButton = app.buttons["nextButton"]
        XCTAssertTrue(nextButton.waitForExistence(timeout: 3), "Next button missing on page 1")
        nextButton.tap()

        XCTAssertTrue(
            app.otherElements["onboardingPage2"].waitForExistence(timeout: 3),
            "Page 2 did not render after tapping Next — ViewModel lifecycle bug regression"
        )
        nextButton.tap()

        XCTAssertTrue(
            app.otherElements["onboardingPage3"].waitForExistence(timeout: 3),
            "Page 3 did not render after tapping Next"
        )

        let getStartedButton = app.buttons["getStartedButton"]
        XCTAssertTrue(
            getStartedButton.waitForExistence(timeout: 3),
            "Get Started button missing on last page"
        )
        getStartedButton.tap()

        // "Knit Note" is the `app_name` key, which resolves to the same
        // literal in both en and ja (see androidApp/res/values{,-ja}/strings.xml).
        // If that ever diverges, pivot this assertion to a testTag landmark.
        XCTAssertTrue(
            app.navigationBars["Knit Note"].waitForExistence(timeout: 5),
            "Onboarding did not complete — ProjectList never appeared"
        )
    }

    func testSkipButtonCompletesOnboarding() {
        app.launch()

        XCTAssertTrue(
            app.otherElements["onboardingPage1"].waitForExistence(timeout: 5),
            "Onboarding page 1 did not appear"
        )

        let skipButton = app.buttons["skipButton"]
        XCTAssertTrue(skipButton.waitForExistence(timeout: 3), "Skip button missing")
        skipButton.tap()

        // See the note above on `app_name` locale-identity.
        XCTAssertTrue(
            app.navigationBars["Knit Note"].waitForExistence(timeout: 5),
            "Skip did not complete onboarding — ProjectList never appeared"
        )
    }
}
