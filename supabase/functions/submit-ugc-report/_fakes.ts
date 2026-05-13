// Pre-alpha A1/A5 — test fakes for submit-ugc-report Edge Function.
//
// Mirrors submit-bug-report/_fakes.ts: monkey-patch `globalThis.fetch`
// so unit tests can arrange per-URL responses without hitting GitHub
// or Supabase. Also generates a real RSA-2048 PEM via WebCrypto
// (re-using the submit-bug-report pattern), and synthesises a minimal
// well-formed JWT (signature ignored — verify_jwt = true means
// production calls are pre-validated by Supabase platform).

// ---------------------------------------------------------------------
// fetch monkey-patch
// ---------------------------------------------------------------------

export type FakeResponder = (req: Request) => Promise<Response> | Response;

export interface FakeFetchHandle {
  readonly calls: ReadonlyArray<{ url: string; method: string; body?: string }>;
  restore(): void;
}

const originalFetch = globalThis.fetch;

export function installFakeFetch(responder: FakeResponder): FakeFetchHandle {
  const calls: { url: string; method: string; body?: string }[] = [];
  globalThis.fetch = async (
    input: RequestInfo | URL,
    init?: RequestInit,
  ): Promise<Response> => {
    const req = input instanceof Request ? input : new Request(input, init);
    const url = req.url;
    const method = req.method;
    let body: string | undefined;
    if (req.bodyUsed === false && method !== "GET" && method !== "HEAD") {
      try {
        body = await req.clone().text();
      } catch {
        body = undefined;
      }
    }
    calls.push({ url, method, body });
    return await responder(req);
  };
  return {
    calls,
    restore() {
      globalThis.fetch = originalFetch;
    },
  };
}

export function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

// ---------------------------------------------------------------------
// Test credential generation
// ---------------------------------------------------------------------

export interface TestGithubAppCredentials {
  appId: string;
  installationId: string;
  privateKeyPem: string;
}

export async function generateTestGithubAppCredentials(): Promise<
  TestGithubAppCredentials
> {
  const keyPair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const pkcs8 = await crypto.subtle.exportKey("pkcs8", keyPair.privateKey);
  const b64 = base64Encode(new Uint8Array(pkcs8));
  const wrapped = b64.match(/.{1,64}/g)?.join("\n") ?? b64;
  const pem =
    `-----BEGIN PRIVATE KEY-----\n${wrapped}\n-----END PRIVATE KEY-----`;
  return {
    appId: "123456",
    installationId: "789012",
    privateKeyPem: pem,
  };
}

// ---------------------------------------------------------------------
// JWT synthesis (signature is garbage — verify_jwt at the function
// boundary is Supabase platform's responsibility, so tests bypass it).
// ---------------------------------------------------------------------

/** Build a fake Bearer header carrying a JWT with the given `sub` claim.
 *  Header + signature segments are constants; only the payload is
 *  meaningful to our extractUserIdFromAuthHeader decoder. */
export function fakeBearerForUser(userId: string): string {
  const header = base64UrlEncode(`{"alg":"HS256","typ":"JWT"}`);
  const payload = base64UrlEncode(
    JSON.stringify({ sub: userId, role: "authenticated" }),
  );
  const signature = "fake-signature-not-verified";
  return `Bearer ${header}.${payload}.${signature}`;
}

function base64UrlEncode(s: string): string {
  return btoa(s).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function base64Encode(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    s += String.fromCharCode(bytes[i]);
  }
  return btoa(s);
}
