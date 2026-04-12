import XCTest

final class ProfileUITests: XCTestCase {

    private let app = XCUIApplication()

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app.launchClean()
    }

    func testDefaultState_displaysProfileScreen() {
        app.buttons["moreMenu"].tapToolbarButton()
        let profileButton = app.buttons["Profile"]
        XCTAssertTrue(profileButton.waitForExistence(timeout: 2), "Profile button not found")
        profileButton.tap()

        XCTAssertTrue(app.navigationBars["Profile"].waitForExistence(timeout: 3))
    }
}
