import XCTest

final class NavigationFlowUITests: XCTestCase {

    private let app = XCUIApplication()

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app.launchClean()
    }

    func testStartDestination_isProjectList() {
        // Sprint A pivot: navigationTitle removed from ProjectListScreen, so
        // the screen-level `accessibilityIdentifier("projectListScreen")` is
        // the new locale-independent anchor.
        XCTAssertTrue(app.otherElements["projectListScreen"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.otherElements["emptyStateLabel"].exists)
    }

    func testNavigateToProfile_andBack() {
        app.buttons["moreMenu"].tapToolbarButton()
        // `profileButton` accessibilityIdentifier is locale-independent —
        // the visible label resolves via i18n in a future iOS ProjectList PR.
        let profileButton = app.buttons["profileButton"]
        XCTAssertTrue(profileButton.waitForExistence(timeout: 2), "Profile button not found")
        profileButton.tap()

        // ProfileScreen root is tagged `profileScreen`; navigation bar title
        // is now i18n'd so we assert the landmark instead of the title text.
        XCTAssertTrue(app.otherElements["profileScreen"].waitForExistence(timeout: 3))

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.otherElements["projectListScreen"].waitForExistence(timeout: 3))
    }

    func testDeepLink_invalidURL_doesNotNavigate() {
        // Open an invalid deep link — app should stay on project list
        let invalidURL = URL(string: "knitnote://share/not-a-uuid")!
        app.open(invalidURL)

        // Should remain on project list
        XCTAssertTrue(app.otherElements["projectListScreen"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.otherElements["emptyStateLabel"].waitForExistence(timeout: 3), "Expected empty state after invalid deep link")
    }

    func testDeepLink_validToken_localOnlyMode_staysOnProjectList() {
        // In local-only mode (no Supabase), a valid deep link should not crash
        let validUUID = "12345678-1234-1234-1234-123456789abc"
        let deepLinkURL = URL(string: "knitnote://share/\(validUUID)")!
        app.open(deepLinkURL)

        // App should remain on project list, not navigate to shared content
        XCTAssertTrue(app.otherElements["projectListScreen"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.otherElements["emptyStateLabel"].waitForExistence(timeout: 3), "Expected empty state after deep link in local mode")
        // Phase 33.1.13: pivoted from navigationBars["Shared Content"] to the
        // accessibilityIdentifier landmark so the assert stays valid on a
        // ja-locale simulator. (The prior string also did not match the actual
        // nav-bar title "Shared Pattern".)
        XCTAssertFalse(app.otherElements["sharedContentScreen"].exists)
    }

    func testNavigateToActivityFeed_andBack() {
        app.buttons["moreMenu"].tapToolbarButton()
        let activityButton = app.buttons["activityFeedButton"]
        XCTAssertTrue(activityButton.waitForExistence(timeout: 2), "Activity Feed button not found")
        activityButton.tap()

        XCTAssertTrue(app.otherElements["activityFeedScreen"].waitForExistence(timeout: 3))

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.otherElements["projectListScreen"].waitForExistence(timeout: 3))
    }
}
