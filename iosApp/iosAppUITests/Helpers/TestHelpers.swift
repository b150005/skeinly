import XCTest

extension XCUIApplication {
    /// Launch the app with a clean database for test isolation.
    /// Automatically completes onboarding if it appears on first launch.
    func launchClean() {
        launchArguments = ["--reset-database"]
        launch()
        completeOnboardingIfPresent()
    }

    /// Swipe through onboarding and tap "Get Started" if the onboarding screen is visible.
    func completeOnboardingIfPresent() {
        let onboardingTitle = staticTexts["Track Your Knitting Projects"]
        guard onboardingTitle.waitForExistence(timeout: 3) else { return }

        // Swipe through all pages
        swipeLeft()
        swipeLeft()

        // Tap "Get Started" on the last page
        let getStarted = buttons["getStartedButton"]
        if getStarted.waitForExistence(timeout: 3) {
            getStarted.tap()
        }

        // Wait for onboarding to dismiss and ProjectList to appear
        let navTitle = navigationBars["Knit Note"]
        _ = navTitle.waitForExistence(timeout: 5)
    }
}

extension XCUIElement {
    /// Tap a navigation bar button that may fail `scrollToVisible`.
    /// Falls back to coordinate-based tap when the AX scroll action fails.
    func tapToolbarButton(timeout: TimeInterval = 5) {
        XCTAssertTrue(waitForExistence(timeout: timeout), "Element '\(identifier)' not found")
        if isHittable {
            tap()
        } else {
            coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
    }
}

/// Create a project through the UI. Assumes the app is on ProjectListScreen.
/// Returns after the project appears in the list.
func createProject(
    in app: XCUIApplication,
    title: String,
    totalRows: String? = nil
) {
    app.buttons["createProjectButton"].tap()

    let titleField = app.textFields["Project Title"]
    XCTAssertTrue(titleField.waitForExistence(timeout: 3), "Project Title field not found")
    titleField.tap()
    titleField.typeText(title)

    if let rows = totalRows {
        let rowsField = app.textFields["Total Rows (optional)"]
        rowsField.tap()
        rowsField.typeText(rows)
    }

    app.buttons["Create"].tap()

    // Wait for the project to appear in the list
    let projectCell = app.staticTexts[title]
    XCTAssertTrue(projectCell.waitForExistence(timeout: 5), "Project '\(title)' did not appear in the list after creation")
}
