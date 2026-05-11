// Phase 39 W5 — test fakes for submit-bug-report Edge Function.
//
// Mirrors the notify-on-write _fakes.ts pattern: monkey-patch
// `globalThis.fetch` so unit tests can arrange per-URL responses
// without hitting GitHub.com. Also exposes a helper that generates
// real RSA-2048 PKCS#1 PEM bytes via WebCrypto's generateKey +
// exportKey path — no production key is ever committed to the repo.

// ---------------------------------------------------------------------
// fetch monkey-patch
// ---------------------------------------------------------------------

export type FakeResponder = (req: Request) => Promise<Response> | Response;

export interface FakeFetchHandle {
    readonly calls: ReadonlyArray<{ url: string; method: string; body?: string }>;
    restore(): void;
}

const originalFetch = globalThis.fetch;

/**
 * Install a fake `fetch` that routes each call through `responder`.
 * Returns a handle exposing the captured call log + a restore function.
 *
 * Tests MUST call `handle.restore()` (typically in a try/finally) to
 * avoid leaking the patch into subsequent tests.
 */
export function installFakeFetch(responder: FakeResponder): FakeFetchHandle {
    const calls: { url: string; method: string; body?: string }[] = [];
    globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
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

/**
 * Generate a real RSA-2048 keypair via WebCrypto, export the private
 * key as PKCS#8 PEM, and return App-shaped credentials. The signing
 * path in `github_app.ts` runs against this PEM exactly as it would
 * against a real GitHub-issued key — only the public-key verification
 * step (which lives on GitHub's side in production) is skipped, since
 * tests stub the token-exchange response.
 *
 * Exports as PKCS#8 ("-----BEGIN PRIVATE KEY-----") because that
 * matches WebCrypto's native export format. The `importRsaPrivateKey`
 * helper in github_app.ts accepts both PKCS#1 and PKCS#8 so either
 * works; we choose PKCS#8 here to avoid round-tripping through the
 * PKCS#1 wrapping path in tests (which would mask a regression in
 * the wrapping code by happily reading what we just wrote).
 */
export async function generateTestGithubAppCredentials(): Promise<TestGithubAppCredentials> {
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
    const pem = `-----BEGIN PRIVATE KEY-----\n${wrapped}\n-----END PRIVATE KEY-----`;
    return {
        appId: "123456",
        installationId: "789012",
        privateKeyPem: pem,
    };
}

function base64Encode(bytes: Uint8Array): string {
    let s = "";
    for (let i = 0; i < bytes.byteLength; i++) {
        s += String.fromCharCode(bytes[i]);
    }
    return btoa(s);
}
