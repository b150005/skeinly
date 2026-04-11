import XCTest

extension XCUIApplication {
    /// Launch the app with a clean database for test isolation.
    func launchClean() {
        launchArguments = ["--reset-database"]
        launch()
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
    _ = titleField.waitForExistence(timeout: 3)
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
    _ = projectCell.waitForExistence(timeout: 5)
}
