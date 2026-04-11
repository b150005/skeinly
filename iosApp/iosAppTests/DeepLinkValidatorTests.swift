import Testing

@Suite("Deep Link Validator")
struct DeepLinkValidatorTests {

    @Test("Valid UUID share token is accepted")
    func validUUID() {
        #expect(isValidShareToken("550e8400-e29b-41d4-a716-446655440000"))
        #expect(isValidShareToken("ABCDEF01-2345-6789-abcd-ef0123456789"))
    }

    @Test("Invalid share tokens are rejected", arguments: [
        "not-a-uuid",
        "550e8400-e29b-41d4-a716",
        "550e8400e29b41d4a716446655440000",
        "",
        "550e8400-e29b-41d4-a716-44665544000g",
        "550e8400-e29b-41d4-a716-4466554400000",
    ])
    func invalidTokens(token: String) {
        #expect(!isValidShareToken(token))
    }
}
