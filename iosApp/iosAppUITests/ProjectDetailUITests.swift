import XCTest

final class ProjectDetailUITests: XCTestCase {

    private let app = XCUIApplication()

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app.launchClean()
        createProject(in: app, title: "Detail Test", totalRows: "50")
        app.staticTexts["Detail Test"].tap()
        XCTAssertTrue(app.staticTexts["rowCounter"].waitForExistence(timeout: 3), "Row counter not found")
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
        // Phase 33.1.12: pivoted from English-label queries
        // ("Mark Complete" / "Reopen Project") to accessibilityIdentifier
        // so the test stays stable when the ja locale resolves the button
        // labels differently.
        let markComplete = app.buttons["markCompleteButton"]
        XCTAssertTrue(markComplete.waitForExistence(timeout: 3), "Mark Complete button not found")
        if !markComplete.isHittable {
            app.swipeUp()
            XCTAssertTrue(markComplete.waitForExistence(timeout: 2), "Mark Complete button not visible after scroll")
            XCTAssertTrue(markComplete.isHittable, "Mark Complete button exists but is not tappable after scroll")
        }
        markComplete.tap()

        XCTAssertTrue(app.buttons["reopenProjectButton"].waitForExistence(timeout: 3))
    }

    func testEmptyNotes_showsPlaceholder() {
        app.swipeUp()
        // Phase 33.1.12: pivoted from english staticTexts["No notes yet"] to
        // an accessibilityIdentifier for locale independence.
        XCTAssertTrue(app.staticTexts["noNotesLabel"].waitForExistence(timeout: 3))
    }
}
