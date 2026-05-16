// Pre-Phase-40 A20 Option B — test fakes for export-my-data.
//
// No GitHub App / RSA-PEM machinery (unlike submit-ugc-report) — this
// function has NO new secret surface. We only need to synthesize a
// minimal well-formed JWT carrying a `sub` claim (signature is garbage:
// verify_jwt = true means production callers are pre-validated by the
// Supabase platform, so extractUserIdFromAuthHeader never verifies the
// signature itself).

/** Build a fake Bearer header carrying a JWT with the given `sub`. */
export function fakeBearerForUser(userId: string): string {
  const header = base64UrlEncode(`{"alg":"HS256","typ":"JWT"}`);
  const payload = base64UrlEncode(
    JSON.stringify({ sub: userId, role: "authenticated" }),
  );
  return `Bearer ${header}.${payload}.fake-signature-not-verified`;
}

function base64UrlEncode(s: string): string {
  // Encode via UTF-8 bytes so a non-ASCII test fixture (e.g. a crafted
  // claim) doesn't throw InvalidCharacterError from raw btoa.
  const bytes = new TextEncoder().encode(s);
  return btoa(String.fromCharCode(...bytes))
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}
