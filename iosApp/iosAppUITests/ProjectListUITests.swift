import XCTest

final class ProjectListUITests: XCTestCase {

    private let app = XCUIApplication()

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app.launchClean()
    }

    func testEmptyState_displaysNoProjectsMessage() {
        // Phase 33.5: pivoted from `app.staticTexts["No Projects Yet"]` to
        // the landmark accessibilityIdentifier on the ContentUnavailableView
        // Label — robust once the app runs under a non-English locale.
        XCTAssertTrue(app.staticTexts["emptyStateLabel"].waitForExistence(timeout: 5))
    }

    func testPlusButton_opensCreateSheet() {
        app.buttons["createProjectFab"].tap()
        XCTAssertTrue(app.textFields["projectNameInput"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.textFields["totalRowsInput"].exists)
    }

    func testCreateProject_appearsInList() {
        createProject(in: app, title: "My Scarf")
        XCTAssertTrue(app.staticTexts["My Scarf"].waitForExistence(timeout: 5))
    }

    func testProjectWithProgress_displaysCorrectly() {
        createProject(in: app, title: "Cable Sweater", totalRows: "100")
        app.staticTexts["Cable Sweater"].tap()

        let incrementButton = app.buttons["incrementButton"]
        XCTAssertTrue(incrementButton.waitForExistence(timeout: 3), "Increment button not found")
        let counter = app.staticTexts["rowCounter"]

        // Increment once and wait for UI update
        incrementButton.tap()
        let pred = NSPredicate(format: "label == '1'")
        _ = expectation(for: pred, evaluatedWith: counter, handler: nil)
        waitForExpectations(timeout: 5)

        // Go back and verify list shows progress
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Cable Sweater"].waitForExistence(timeout: 5), "Cable Sweater not found in list")
        // Phase 33.5: pivoted from `app.staticTexts["1 / 100 rows"]` to the
        // `projectRowCount` accessibilityIdentifier. The identifier repeats
        // for every row in the list, but since this test creates exactly one
        // project the single-match query is unambiguous.
        XCTAssertTrue(app.staticTexts["projectRowCount"].waitForExistence(timeout: 5))
    }

    func testTapProject_navigatesToDetail() {
        createProject(in: app, title: "Test Project")
        app.staticTexts["Test Project"].tap()
        XCTAssertTrue(app.staticTexts["rowCounter"].waitForExistence(timeout: 3))
    }

    func testCreateSheet_cancelDismisses() {
        app.buttons["createProjectFab"].tap()
        XCTAssertTrue(app.textFields["projectNameInput"].waitForExistence(timeout: 3), "Title field not found")
        // Phase 33.5: pivoted to the `cancelButton` accessibilityIdentifier on
        // the createProjectSheet toolbar cancel Button.
        app.buttons["cancelButton"].tap()
        XCTAssertTrue(app.staticTexts["emptyStateLabel"].waitForExistence(timeout: 3))
    }
}
