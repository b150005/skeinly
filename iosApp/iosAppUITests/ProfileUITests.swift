import XCTest

final class ProfileUITests: XCTestCase {

    private let app = XCUIApplication()

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app.launchClean()
    }

    func testDefaultState_displaysProfileScreen() {
        app.buttons["moreMenu"].tap()
        let profileButton = app.buttons["Profile"]
        _ = profileButton.waitForExistence(timeout: 2)
        profileButton.tap()

        XCTAssertTrue(app.navigationBars["Profile"].waitForExistence(timeout: 3))
    }
}
