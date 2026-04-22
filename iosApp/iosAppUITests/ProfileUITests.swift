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
        // `profileButton` accessibilityIdentifier is the locale-independent
        // selector for the Profile menu entry. Per docs/en/i18n-convention.md.
        let profileButton = app.buttons["profileButton"]
        XCTAssertTrue(profileButton.waitForExistence(timeout: 2), "Profile button not found")
        profileButton.tap()

        // ProfileScreen root is tagged `profileScreen` — the navigation bar
        // title is now i18n'd (`title_profile`) so querying it by the English
        // literal "Profile" breaks on ja-locale simulators.
        XCTAssertTrue(app.otherElements["profileScreen"].waitForExistence(timeout: 3))
    }
}
