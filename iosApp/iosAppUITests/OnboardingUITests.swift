import XCTest

/// Regression tests for the onboarding flow. These guard against the
/// SwiftUI struct re-init bug where a Koin-resolved ViewModel was created
/// fresh on every View init, causing button taps to dispatch events to an
/// orphan ViewModel while the observer stayed bound to the original state flow.
/// The ScopedViewModel holder fix pins the ViewModel via @StateObject so
/// these flows complete deterministically.
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
            app.staticTexts["Track Your Knitting Projects"].waitForExistence(timeout: 5),
            "Onboarding page 1 did not appear"
        )

        let nextButton = app.buttons["nextButton"]
        XCTAssertTrue(nextButton.waitForExistence(timeout: 3), "Next button missing on page 1")
        nextButton.tap()

        XCTAssertTrue(
            app.staticTexts["Count Every Stitch"].waitForExistence(timeout: 3),
            "Page 2 did not render after tapping Next — ViewModel lifecycle bug regression"
        )
        nextButton.tap()

        XCTAssertTrue(
            app.staticTexts["Build Your Pattern Library"].waitForExistence(timeout: 3),
            "Page 3 did not render after tapping Next"
        )

        let getStartedButton = app.buttons["getStartedButton"]
        XCTAssertTrue(
            getStartedButton.waitForExistence(timeout: 3),
            "Get Started button missing on last page"
        )
        getStartedButton.tap()

        XCTAssertTrue(
            app.navigationBars["Knit Note"].waitForExistence(timeout: 5),
            "Onboarding did not complete — ProjectList never appeared"
        )
    }

    func testSkipButtonCompletesOnboarding() {
        app.launch()

        XCTAssertTrue(
            app.staticTexts["Track Your Knitting Projects"].waitForExistence(timeout: 5),
            "Onboarding page 1 did not appear"
        )

        let skipButton = app.buttons["skipButton"]
        XCTAssertTrue(skipButton.waitForExistence(timeout: 3), "Skip button missing")
        skipButton.tap()

        XCTAssertTrue(
            app.navigationBars["Knit Note"].waitForExistence(timeout: 5),
            "Skip did not complete onboarding — ProjectList never appeared"
        )
    }
}
