// Phase 24.3 (ADR-018 §3.2, §3.4): FCM HTTP v1 push send path.
//
// FCM v1 endpoint: `https://fcm.googleapis.com/v1/projects/<project-id>/messages:send`
// Auth: short-lived OAuth 2.0 access token minted from the Firebase
// service account JWT (RS256). Per-instance memoization with
// `FCM_TOKEN_REFRESH_MARGIN_MS` safety margin (ADR-018 §3.2).
//
// Token cleanup classification per §3.4: HTTP 404 UNREGISTERED, HTTP 403
// SENDER_ID_MISMATCH warrant DELETE; UNAUTHENTICATED triggers a single
// retry after refresh; everything else log + continue (no DELETE).

import { create as createJwt, getNumericDate } from "jsr:@zaubrik/djwt@^3";

import type { TemplateKey } from "./mapping.ts";
import type { SendOutcome } from "./apns.ts";

// ---------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------

const GOOGLE_OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";
const FCM_BASE_URL = "https://fcm.googleapis.com/v1/projects";
const FCM_OAUTH_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

/** ADR-018 §3.2: 5-min margin before SA OAuth access token expiry.
 * Avoids the boundary case where a token expires mid-flight between
 * cache hit and FCM POST. Google access tokens have a 1-hour TTL so
 * 5 minutes is generous slack. */
const FCM_TOKEN_REFRESH_MARGIN_MS = 5 * 60 * 1000;

const FCM_NOTIFICATION_TITLE = "Skeinly";

// ---------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------

export interface ServiceAccount {
    type: string;
    project_id: string;
    private_key_id: string;
    private_key: string;
    client_email: string;
}

export interface FcmSendInput {
    deviceToken: string;
    body: string;
    /** Carried for Phase 24.5 deep-link routing parity with APNs. */
    templateKey: TemplateKey;
}

// ---------------------------------------------------------------------
// OAuth access token cache (per-instance memoization)
// ---------------------------------------------------------------------

interface FcmTokenCacheEntry {
    value: string;
    expiresAt: number;
}

let cachedFcmAccessToken: FcmTokenCacheEntry | null = null;

/** Test-only — drains the cache so unit tests can re-run from a clean state. */
export function _resetFcmAccessTokenCacheForTests(): void {
    cachedFcmAccessToken = null;
}

/**
 * Mint or reuse the per-instance FCM access token. ADR-018 §3.2.
 */
export async function getFcmAccessToken(sa: ServiceAccount): Promise<string> {
    const now = Date.now();
    if (cachedFcmAccessToken && cachedFcmAccessToken.expiresAt - now > FCM_TOKEN_REFRESH_MARGIN_MS) {
        return cachedFcmAccessToken.value;
    }
    const fresh = await fetchFcmAccessToken(sa);
    cachedFcmAccessToken = {
        value: fresh.accessToken,
        expiresAt: now + (fresh.expiresInSeconds * 1000),
    };
    return fresh.accessToken;
}

interface AccessTokenResult {
    accessToken: string;
    expiresInSeconds: number;
}

async function fetchFcmAccessToken(sa: ServiceAccount): Promise<AccessTokenResult> {
    const cryptoKey = await importPkcs8RsaKey(sa.private_key);
    const now = getNumericDate(0);
    const exp = getNumericDate(60 * 60); // 1 hour
    const jwt = await createJwt(
        { alg: "RS256", typ: "JWT", kid: sa.private_key_id },
        {
            iss: sa.client_email,
            scope: FCM_OAUTH_SCOPE,
            aud: GOOGLE_OAUTH_TOKEN_URL,
            iat: now,
            exp,
        },
        cryptoKey,
    );

    const tokenResponse = await fetch(GOOGLE_OAUTH_TOKEN_URL, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
            grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
            assertion: jwt,
        }),
    });

    if (!tokenResponse.ok) {
        let detail = "";
        try {
            detail = await tokenResponse.text();
        } catch {
            // ignore
        }
        throw new Error(`fcm_oauth_failed: status=${tokenResponse.status} detail=${detail.slice(0, 200)}`);
    }

    const tokenJson = await tokenResponse.json() as {
        access_token?: string;
        expires_in?: number;
    };
    if (!tokenJson.access_token || typeof tokenJson.expires_in !== "number") {
        throw new Error("fcm_oauth_response_malformed");
    }
    return {
        accessToken: tokenJson.access_token,
        expiresInSeconds: tokenJson.expires_in,
    };
}

async function importPkcs8RsaKey(pem: string): Promise<CryptoKey> {
    const pkcs8Body = pem
        .replace(/-----BEGIN [^-]+-----/g, "")
        .replace(/-----END [^-]+-----/g, "")
        .replace(/\s+/g, "");
    const pkcs8Buffer = base64DecodeToBuffer(pkcs8Body);
    return await crypto.subtle.importKey(
        "pkcs8",
        pkcs8Buffer,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["sign"],
    );
}

function base64DecodeToBuffer(input: string): ArrayBuffer {
    const binary = atob(input);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out.buffer;
}

// ---------------------------------------------------------------------
// Send
// ---------------------------------------------------------------------

/**
 * Send one FCM v1 push. ADR-018 §3.4 401 retry shape: a single retry
 * after a forced cache reset. Any further 401 is a config error
 * (SA permissions revoked, etc.) and surfaces as such.
 */
export async function sendFcm(
    sa: ServiceAccount,
    input: FcmSendInput,
): Promise<SendOutcome> {
    const firstAttempt = await trySendFcm(sa, input);
    if (firstAttempt.kind !== "transient_error" || firstAttempt.reason !== "fcm_unauth_retry_pending") {
        return firstAttempt;
    }
    // 401 → drain cache + try once more. Defensive against a stale
    // access token that survived the margin check (e.g. clock skew on
    // the Edge Function instance vs. Google).
    cachedFcmAccessToken = null;
    return await trySendFcm(sa, input);
}

async function trySendFcm(sa: ServiceAccount, input: FcmSendInput): Promise<SendOutcome> {
    let accessToken: string;
    try {
        accessToken = await getFcmAccessToken(sa);
    } catch (e) {
        return { kind: "config_error", reason: `fcm_oauth_fetch_failed: ${stringifyError(e)}` };
    }

    const url = `${FCM_BASE_URL}/${sa.project_id}/messages:send`;
    const messageBody = {
        message: {
            token: input.deviceToken,
            notification: {
                title: FCM_NOTIFICATION_TITLE,
                body: input.body,
            },
        },
    };

    let response: Response;
    try {
        response = await fetch(url, {
            method: "POST",
            headers: {
                "Authorization": `Bearer ${accessToken}`,
                "Content-Type": "application/json",
            },
            body: JSON.stringify(messageBody),
        });
    } catch (e) {
        return { kind: "transient_error", reason: `fcm_fetch_failed: ${stringifyError(e)}` };
    }

    if (response.status === 200) {
        await response.body?.cancel();
        return { kind: "success" };
    }

    let errorCode: string | null = null;
    try {
        const errorBody = await response.json() as {
            error?: {
                status?: string;
                details?: Array<{ errorCode?: string }>;
            };
        };
        // Prefer the per-detail errorCode (e.g. "UNREGISTERED") over
        // the higher-level status (e.g. "NOT_FOUND") because FCM uses
        // the per-detail code for the canonical token-state signal.
        errorCode = errorBody.error?.details?.[0]?.errorCode
            ?? errorBody.error?.status
            ?? null;
    } catch {
        // Non-JSON body (rare — gateway 502, etc.). Classifier handles
        // null reason as transient by status code.
    }

    return classifyFcmResponse(response.status, errorCode);
}

// ---------------------------------------------------------------------
// Response classification (ADR-018 §3.4)
// ---------------------------------------------------------------------

const FCM_DELETE_CODES = new Set<string>([
    "UNREGISTERED",
    "SENDER_ID_MISMATCH",
]);

/**
 * Classify an FCM v1 response. UNAUTHENTICATED returns a sentinel
 * `fcm_unauth_retry_pending` reason that the `sendFcm` outer wrapper
 * recognizes as the trigger for a single force-refresh retry. All
 * unknown 4xx/5xx fall through to transient_error.
 */
export function classifyFcmResponse(httpStatus: number, errorCode: string | null): SendOutcome {
    if (httpStatus === 200) return { kind: "success" };
    if (errorCode && FCM_DELETE_CODES.has(errorCode)) {
        return { kind: "delete_token", reason: errorCode };
    }
    if (httpStatus === 401 || errorCode === "UNAUTHENTICATED") {
        return { kind: "transient_error", reason: "fcm_unauth_retry_pending" };
    }
    if (httpStatus === 403 && errorCode !== "SENDER_ID_MISMATCH") {
        // 403 without SENDER_ID_MISMATCH is a config error — SA missing
        // FCM scope, or project-level permission revoked.
        return { kind: "config_error", reason: errorCode ?? "fcm_403" };
    }
    if (httpStatus === 404 && !errorCode) {
        // Defensive: treat ambiguous 404 (missing errorCode) as config
        // rather than delete — the FCM URL itself may be wrong.
        return { kind: "config_error", reason: "fcm_404" };
    }
    if (httpStatus >= 500 || httpStatus === 429) {
        return { kind: "transient_error", reason: errorCode ?? `fcm_${httpStatus}` };
    }
    if (httpStatus >= 400 && httpStatus < 500) {
        return { kind: "transient_error", reason: errorCode ?? `fcm_${httpStatus}` };
    }
    return { kind: "transient_error", reason: errorCode ?? `fcm_${httpStatus}` };
}

// ---------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------

function stringifyError(e: unknown): string {
    if (e instanceof Error) return e.message;
    return String(e);
}
