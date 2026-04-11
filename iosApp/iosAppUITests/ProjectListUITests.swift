import XCTest

final class ProjectListUITests: XCTestCase {

    private let app = XCUIApplication()

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app.launchClean()
    }

    func testEmptyState_displaysNoProjectsMessage() {
        XCTAssertTrue(app.staticTexts["No Projects Yet"].waitForExistence(timeout: 5))
    }

    func testPlusButton_opensCreateSheet() {
        app.buttons["createProjectButton"].tap()
        XCTAssertTrue(app.textFields["Project Title"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.textFields["Total Rows (optional)"].exists)
    }

    func testCreateProject_appearsInList() {
        createProject(in: app, title: "My Scarf")
        XCTAssertTrue(app.staticTexts["My Scarf"].waitForExistence(timeout: 5))
    }

    func testProjectWithProgress_displaysCorrectly() {
        createProject(in: app, title: "Cable Sweater", totalRows: "100")
        app.staticTexts["Cable Sweater"].tap()

        let incrementButton = app.buttons["incrementButton"]
        _ = incrementButton.waitForExistence(timeout: 3)
        let counter = app.staticTexts["rowCounter"]

        // Increment once and wait for UI update
        incrementButton.tap()
        let pred = NSPredicate(format: "label == '1'")
        _ = expectation(for: pred, evaluatedWith: counter, handler: nil)
        waitForExpectations(timeout: 5)

        // Go back and verify list shows progress
        app.navigationBars.buttons.firstMatch.tap()
        _ = app.staticTexts["Cable Sweater"].waitForExistence(timeout: 5)
        XCTAssertTrue(app.staticTexts["1 / 100 rows"].waitForExistence(timeout: 5))
    }

    func testTapProject_navigatesToDetail() {
        createProject(in: app, title: "Test Project")
        app.staticTexts["Test Project"].tap()
        XCTAssertTrue(app.staticTexts["rowCounter"].waitForExistence(timeout: 3))
    }

    func testCreateSheet_cancelDismisses() {
        app.buttons["createProjectButton"].tap()
        _ = app.textFields["Project Title"].waitForExistence(timeout: 3)
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.staticTexts["No Projects Yet"].waitForExistence(timeout: 3))
    }
}
