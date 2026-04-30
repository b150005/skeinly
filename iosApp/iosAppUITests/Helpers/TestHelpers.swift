import XCTest

extension XCUIApplication {
    /// Launch the app with a clean database for test isolation.
    /// Automatically completes onboarding if it appears on first launch.
    func launchClean() {
        launchArguments = ["--reset-database"]
        launch()
        completeOnboardingIfPresent()
    }

    /// Skip onboarding if it appears on first launch.
    /// Uses the "Skip" button for reliability on CI (swipe gestures on TabView are flaky).
    func completeOnboardingIfPresent() {
        let skipButton = buttons["skipButton"]
        guard skipButton.waitForExistence(timeout: 3) else { return }
        skipButton.tap()

        // Wait for onboarding to dismiss and ProjectList to appear.
        // Sprint A removed the `.navigationTitle` so we anchor on the FAB,
        // which is always visible on ProjectList regardless of empty/non-empty.
        let fab = buttons["createProjectFab"]
        _ = fab.waitForExistence(timeout: 5)
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
    app.buttons["createProjectFab"].tap()

    let titleField = app.textFields["projectNameInput"]
    XCTAssertTrue(titleField.waitForExistence(timeout: 3), "Title field not found")
    titleField.tap()
    titleField.typeText(title)

    if let rows = totalRows {
        let rowsField = app.textFields["totalRowsInput"]
        rowsField.tap()
        rowsField.typeText(rows)
    }

    app.buttons["createProjectButton"].tap()

    // Wait for the project to appear in the list
    let projectCell = app.staticTexts[title]
    XCTAssertTrue(projectCell.waitForExistence(timeout: 5), "Project '\(title)' did not appear in the list after creation")
}
