// Phase 24.3 (ADR-018 §3.6): test-only HTTP fakes + key fixtures.
//
// `globalThis.fetch` monkey-patch that lets test files arrange specific
// APNs / FCM / OAuth responses then invoke production code through the
// dispatch path. Underscore-prefixed file name marks "test-only, never
// imported by production code" — `index.ts` / `apns.ts` / `fcm.ts` do
// NOT import this module.

interface FakeApnsResponse {
    status: number;
    reason?: string;
}

interface FakeFcmResponse {
    status: number;
    errorCode?: string;
    /** Optional override for the high-level `error.status` field. */
    statusName?: string;
}

interface FakeOAuthResponse {
    status: number;
    accessToken?: string;
    expiresIn?: number;
    body?: unknown;
}

export interface FetchFake {
    /** Map APNs device-token → response. Token is the path tail of
     * `/3/device/<token>`. */
    setApnsResponse(token: string, response: FakeApnsResponse): void;
    /** Map FCM device-token → response. */
    setFcmResponse(token: string, response: FakeFcmResponse): void;
    /** Override the OAuth token endpoint response. */
    setOAuthResponse(response: FakeOAuthResponse): void;
    /** Number of fetch calls observed. Useful for asserting "called
     * exactly once" / "OAuth called once across N pushes". */
    callCount(): number;
    /** Number of OAuth-endpoint hits specifically. */
    oauthCallCount(): number;
    /** Snapshot last-arrange state (for failure-mode debugging). */
    snapshotRequests(): Array<{ url: string; method: string }>;
    /** Replace `globalThis.fetch` until restore() is called. */
    install(): void;
    /** Put the original `globalThis.fetch` back. */
    restore(): void;
}

export function createFetchFake(): FetchFake {
    const apnsResponses = new Map<string, FakeApnsResponse>();
    const fcmResponses = new Map<string, FakeFcmResponse>();
    let oauthResponse: FakeOAuthResponse = {
        status: 200,
        accessToken: "fake-access-token-default",
        expiresIn: 3600,
    };
    let totalCalls = 0;
    let oauthCalls = 0;
    const requests: Array<{ url: string; method: string }> = [];

    const originalFetch = globalThis.fetch;

    // The function signature `typeof fetch` requires `Promise<Response>` —
    // most arms construct Response synchronously but the signature contract
    // forces `async` so all return values normalize to Promise.
    // deno-lint-ignore require-await
    const fakeFetch: typeof fetch = async (input, init) => {
        totalCalls += 1;
        const url = typeof input === "string"
            ? input
            : input instanceof URL
                ? input.toString()
                : input.url;
        // Cast — RequestInit's method/body are universally available across
        // Deno's overloaded fetch signature, but TS can't always prove the
        // intersection at the union type level.
        const initShape = (init ?? {}) as { method?: string; body?: BodyInit | null };
        const method = initShape.method
            ?? (input instanceof Request ? input.method : "GET");
        requests.push({ url, method });

        if (url === "https://oauth2.googleapis.com/token") {
            oauthCalls += 1;
            if (oauthResponse.status !== 200) {
                return jsonResponse(
                    oauthResponse.body ?? { error: "oauth_fake_error" },
                    oauthResponse.status,
                );
            }
            return jsonResponse(
                {
                    access_token: oauthResponse.accessToken ?? "fake-access-token",
                    expires_in: oauthResponse.expiresIn ?? 3600,
                    token_type: "Bearer",
                },
                200,
            );
        }

        if (url.startsWith("https://api.push.apple.com/3/device/")) {
            const token = url.substring("https://api.push.apple.com/3/device/".length);
            const fake = apnsResponses.get(token);
            if (!fake) {
                throw new Error(`fetch fake: no APNs response arranged for token=${token}`);
            }
            if (fake.status === 200) return new Response(null, { status: 200 });
            return jsonResponse(
                fake.reason ? { reason: fake.reason } : {},
                fake.status,
            );
        }

        if (url.startsWith("https://fcm.googleapis.com/v1/projects/")) {
            const body = initShape.body ? JSON.parse(String(initShape.body)) : null;
            const token = body?.message?.token as string | undefined;
            if (!token) {
                throw new Error("fetch fake: FCM POST missing message.token");
            }
            const fake = fcmResponses.get(token);
            if (!fake) {
                throw new Error(`fetch fake: no FCM response arranged for token=${token}`);
            }
            if (fake.status === 200) {
                return jsonResponse({ name: `projects/_/messages/${token}` }, 200);
            }
            return jsonResponse(
                buildFcmErrorBody(fake),
                fake.status,
            );
        }

        // Unknown URL — fall through to the real fetch only if explicitly
        // arranged. Default: error to surface accidental misuse.
        throw new Error(`fetch fake: unexpected URL ${url}`);
    };

    return {
        setApnsResponse(token, response) {
            apnsResponses.set(token, response);
        },
        setFcmResponse(token, response) {
            fcmResponses.set(token, response);
        },
        setOAuthResponse(response) {
            oauthResponse = response;
        },
        callCount() {
            return totalCalls;
        },
        oauthCallCount() {
            return oauthCalls;
        },
        snapshotRequests() {
            return [...requests];
        },
        install() {
            globalThis.fetch = fakeFetch;
        },
        restore() {
            globalThis.fetch = originalFetch;
        },
    };
}

function jsonResponse(body: unknown, status: number): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}

function buildFcmErrorBody(fake: FakeFcmResponse): unknown {
    if (!fake.errorCode && !fake.statusName) {
        return {};
    }
    return {
        error: {
            code: fake.status,
            message: fake.errorCode ?? fake.statusName ?? "unknown",
            status: fake.statusName ?? fake.errorCode ?? "UNKNOWN",
            details: fake.errorCode
                ? [{ "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: fake.errorCode }]
                : undefined,
        },
    };
}

// ---------------------------------------------------------------------
// Key fixtures — generated at test runtime via WebCrypto so the
// importKey + createJwt path runs against real cryptographic material
// without committing any real production key to the repo.
// ---------------------------------------------------------------------

export interface TestApnsCredentials {
    keyP8Pem: string;
    keyId: string;
    teamId: string;
}

/** Generate a fresh ECDSA P-256 key pair, export the private half as
 * PKCS8 PEM, return the credentials shape `apns.ts` consumes. */
export async function generateTestApnsCredentials(): Promise<TestApnsCredentials> {
    const { privateKey } = await crypto.subtle.generateKey(
        { name: "ECDSA", namedCurve: "P-256" },
        true,
        ["sign", "verify"],
    );
    const pem = await exportPrivateKeyAsPem(privateKey);
    return {
        keyP8Pem: pem,
        keyId: "TEST123456",
        teamId: "TEAM7890AB",
    };
}

export interface TestServiceAccount {
    type: string;
    project_id: string;
    private_key_id: string;
    private_key: string;
    client_email: string;
}

/** Generate a fresh RSA-2048 key pair, export as PKCS8 PEM, return the
 * SA shape `fcm.ts` consumes. */
export async function generateTestServiceAccount(
    projectId = "test-project",
): Promise<TestServiceAccount> {
    const { privateKey } = await crypto.subtle.generateKey(
        {
            name: "RSASSA-PKCS1-v1_5",
            modulusLength: 2048,
            publicExponent: new Uint8Array([1, 0, 1]),
            hash: "SHA-256",
        },
        true,
        ["sign", "verify"],
    );
    const pem = await exportPrivateKeyAsPem(privateKey);
    return {
        type: "service_account",
        project_id: projectId,
        private_key_id: "TEST_KEY_ID_RSA",
        private_key: pem,
        client_email: `test-sa@${projectId}.iam.gserviceaccount.com`,
    };
}

async function exportPrivateKeyAsPem(privateKey: CryptoKey): Promise<string> {
    const pkcs8 = await crypto.subtle.exportKey("pkcs8", privateKey);
    const base64 = base64Encode(new Uint8Array(pkcs8));
    // 64-char line wrapping per the PEM RFC 7468 layout convention.
    const wrapped = (base64.match(/.{1,64}/g) ?? []).join("\n");
    return `-----BEGIN PRIVATE KEY-----\n${wrapped}\n-----END PRIVATE KEY-----`;
}

function base64Encode(bytes: Uint8Array): string {
    let binary = "";
    for (let i = 0; i < bytes.byteLength; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
}
