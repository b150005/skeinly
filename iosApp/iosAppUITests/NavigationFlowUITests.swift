import XCTest

final class NavigationFlowUITests: XCTestCase {

    private let app = XCUIApplication()

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app.launchClean()
    }

    func testStartDestination_isProjectList() {
        XCTAssertTrue(app.navigationBars["Knit Note"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["No Projects Yet"].exists)
    }

    func testNavigateToProfile_andBack() {
        app.buttons["moreMenu"].tap()
        let profileButton = app.buttons["Profile"]
        _ = profileButton.waitForExistence(timeout: 2)
        profileButton.tap()

        XCTAssertTrue(app.navigationBars["Profile"].waitForExistence(timeout: 3))

        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Knit Note"].waitForExistence(timeout: 3))
    }

    func testNavigateToActivityFeed_andBack() {
        app.buttons["moreMenu"].tap()
        let activityButton = app.buttons["Activity"]
        _ = activityButton.waitForExistence(timeout: 2)
        activityButton.tap()

        XCTAssertTrue(app.navigationBars["Activity Feed"].waitForExistence(timeout: 3))

        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Knit Note"].waitForExistence(timeout: 3))
    }
}
