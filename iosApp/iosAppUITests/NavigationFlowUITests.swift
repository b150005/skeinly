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
        app.buttons["moreMenu"].tapToolbarButton()
        let profileButton = app.buttons["Profile"]
        XCTAssertTrue(profileButton.waitForExistence(timeout: 2), "Profile button not found")
        profileButton.tap()

        XCTAssertTrue(app.navigationBars["Profile"].waitForExistence(timeout: 3))

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["Knit Note"].waitForExistence(timeout: 3))
    }

    func testDeepLink_invalidURL_doesNotNavigate() {
        // Open an invalid deep link — app should stay on project list
        let invalidURL = URL(string: "knitnote://share/not-a-uuid")!
        app.open(invalidURL)

        // Should remain on project list
        XCTAssertTrue(app.navigationBars["Knit Note"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["No Projects Yet"].waitForExistence(timeout: 3), "Expected empty state after invalid deep link")
    }

    func testDeepLink_validToken_localOnlyMode_staysOnProjectList() {
        // In local-only mode (no Supabase), a valid deep link should not crash
        let validUUID = "12345678-1234-1234-1234-123456789abc"
        let deepLinkURL = URL(string: "knitnote://share/\(validUUID)")!
        app.open(deepLinkURL)

        // App should remain on project list, not navigate to shared content
        XCTAssertTrue(app.navigationBars["Knit Note"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["No Projects Yet"].waitForExistence(timeout: 3), "Expected empty state after deep link in local mode")
        XCTAssertFalse(app.navigationBars["Shared Content"].exists)
    }

    func testNavigateToActivityFeed_andBack() {
        app.buttons["moreMenu"].tapToolbarButton()
        let activityButton = app.buttons["Activity"]
        XCTAssertTrue(activityButton.waitForExistence(timeout: 2), "Activity button not found")
        activityButton.tap()

        XCTAssertTrue(app.navigationBars["Activity Feed"].waitForExistence(timeout: 3))

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["Knit Note"].waitForExistence(timeout: 3))
    }
}
