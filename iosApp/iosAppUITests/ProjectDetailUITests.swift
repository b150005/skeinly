import XCTest

final class ProjectDetailUITests: XCTestCase {

    private let app = XCUIApplication()

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app.launchClean()
        createProject(in: app, title: "Detail Test", totalRows: "50")
        app.staticTexts["Detail Test"].tap()
        _ = app.staticTexts["rowCounter"].waitForExistence(timeout: 3)
    }

    func testDisplaysProjectDetails() {
        XCTAssertTrue(app.staticTexts["rowCounter"].exists)
        XCTAssertTrue(app.buttons["incrementButton"].exists)
        XCTAssertTrue(app.buttons["decrementButton"].exists)
    }

    func testIncrementRow_updatesCount() {
        let counter = app.staticTexts["rowCounter"]
        let before = counter.label

        app.buttons["incrementButton"].tap()

        let expected = String((Int(before) ?? 0) + 1)
        XCTAssertTrue(app.staticTexts[expected].waitForExistence(timeout: 3))
    }

    func testDecrementButton_existsAndInitiallyDisabled() {
        let decrementButton = app.buttons["decrementButton"]
        XCTAssertTrue(decrementButton.exists)
        // Decrement is disabled when counter is 0
        XCTAssertFalse(decrementButton.isEnabled)
    }

    func testMarkComplete_showsReopenButton() {
        let markComplete = app.buttons["Mark Complete"]
        if !markComplete.isHittable {
            app.swipeUp()
        }
        markComplete.tap()

        XCTAssertTrue(app.buttons["Reopen Project"].waitForExistence(timeout: 3))
    }

    func testEmptyNotes_showsPlaceholder() {
        app.swipeUp()
        XCTAssertTrue(app.staticTexts["No notes yet"].waitForExistence(timeout: 3))
    }
}
