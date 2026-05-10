// Phase 24.3 (ADR-018 §3.1, §3.4, §3.5): APNs HTTP/2 push send path.
//
// Production-only host (`api.push.apple.com`) per ADR-018 §3.5.
// JWT signing via `djwt` JSR per §3.1 (audited, single source for both
// APNs ES256 and FCM RS256 below).
//
// Token cleanup classification per §3.4: HTTP 410 Unregistered, HTTP 400
// BadDeviceToken, HTTP 400 DeviceTokenNotForTopic warrant DELETE; all
// other 4xx are Edge Function bugs (do not penalize the token); 5xx /
// 429 / unknown reasons fall through to transient_error (fail-safe —
// never delete on an unknown signal).

import { create as createJwt, getNumericDate } from "@zaubrik/djwt";

import type { TemplateKey } from "./mapping.ts";

// ---------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------

/** Phase 24.3 closed beta: production-only path. ADR-018 §3.5 documents
 * the rationale + the Phase 24.4+ pivot to a `device_tokens.environment`
 * column if local-debug push iteration becomes a need. */
const APNS_HOST = "https://api.push.apple.com";

/** Bundle ID — hard-coded per release-secrets.md EF-2 note ("not in
 * secrets"). Mirrors the iOS distribution provisioning profile bundle
 * id documented in vendor-setup A0a-2. */
const APNS_TOPIC = "io.github.b150005.skeinly";

/** APNs token-based provider authentication accepts JWTs up to ~60
 * minutes old; we mint a fresh token per Edge Function instance and
 * cache it inside `apnsJwtCache` until it nears expiry. The 50-minute
 * effective lifetime gives a 10-minute margin under Apple's hard 60-min
 * cap, which exceeds any realistic in-flight delay between cache hit
 * and HTTP/2 POST. */
const APNS_JWT_TTL_SECONDS = 50 * 60;

const APNS_JWT_REFRESH_MARGIN_MS = 5 * 60 * 1000;

/** Title used for every push body. EN/JA collapse to the app name
 * "Skeinly" since the app name is intentionally locale-invariant. */
const APNS_NOTIFICATION_TITLE = "Skeinly";

// ---------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------

/** Outcome of a single APNs send. Phase 24.3 callers route DELETE on
 * `delete_token`, log on `transient_error` / `config_error`. */
export type SendOutcome =
    | { kind: "success" }
    | { kind: "delete_token"; reason: string }
    | { kind: "transient_error"; reason: string }
    | { kind: "config_error"; reason: string };

export interface ApnsCredentials {
    /** Raw PEM body of the .p8 file from APPLE_APNS_KEY_P8 (release-secrets EF-1). */
    keyP8Pem: string;
    /** 10-char key id from APPLE_APNS_KEY_ID (release-secrets EF-2). */
    keyId: string;
    /** 10-char team id from APPLE_TEAM_ID. */
    teamId: string;
}

export interface ApnsSendInput {
    deviceToken: string;
    body: string;
    templateKey: TemplateKey;
    /** Phase 24.5 — host-relative deep-link route, embedded in the
     * APNs payload's top-level `data` dict. iOS reads it from
     * `notification.request.content.userInfo["data"]["route"]` in
     * `UNUserNotificationCenterDelegate`. */
    route: string;
}

// ---------------------------------------------------------------------
// JWT cache (per-instance memoization)
// ---------------------------------------------------------------------

interface ApnsJwtCacheEntry {
    value: string;
    expiresAt: number;
}

let apnsJwtCache: ApnsJwtCacheEntry | null = null;

/** Test-only — drains the cache so unit tests can re-run from a clean state. */
export function _resetApnsJwtCacheForTests(): void {
    apnsJwtCache = null;
}

// ---------------------------------------------------------------------
// JWT signing
// ---------------------------------------------------------------------

/**
 * Mint or reuse the per-instance APNs provider JWT. Cached across calls
 * within the same Edge Function instance, refreshed when within
 * `APNS_JWT_REFRESH_MARGIN_MS` of expiry. ADR-018 §3.2 covers the
 * margin rationale (parallel to the FCM OAuth caching shape).
 */
export async function getApnsJwt(creds: ApnsCredentials): Promise<string> {
    const now = Date.now();
    if (apnsJwtCache && apnsJwtCache.expiresAt - now > APNS_JWT_REFRESH_MARGIN_MS) {
        return apnsJwtCache.value;
    }
    const cryptoKey = await importP8Key(creds.keyP8Pem);
    const jwt = await createJwt(
        { alg: "ES256", typ: "JWT", kid: creds.keyId },
        {
            iss: creds.teamId,
            iat: getNumericDate(0),
        },
        cryptoKey,
    );
    apnsJwtCache = {
        value: jwt,
        expiresAt: now + APNS_JWT_TTL_SECONDS * 1000,
    };
    return jwt;
}

/** Convert the .p8 PEM body into a CryptoKey that djwt's ES256 path
 * accepts. Strips the PEM armor + base64-decodes, then importKey via
 * WebCrypto's `pkcs8` format. ECDSA P-256 + SHA-256 is the curve/hash
 * pair APNs requires. */
async function importP8Key(pem: string): Promise<CryptoKey> {
    const pkcs8Body = pem
        .replace(/-----BEGIN [^-]+-----/g, "")
        .replace(/-----END [^-]+-----/g, "")
        .replace(/\s+/g, "");
    const pkcs8Buffer = base64DecodeToBuffer(pkcs8Body);
    return await crypto.subtle.importKey(
        "pkcs8",
        pkcs8Buffer,
        { name: "ECDSA", namedCurve: "P-256" },
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
 * Send one APNs push. Returns the classified outcome — caller (in
 * `index.ts`) decides whether to DELETE the token row.
 *
 * ADR-018 §3.3: the per-recipient call site invokes this through
 * `Promise.allSettled` so one bad token does not block other tokens
 * for the same recipient.
 */
export async function sendApns(creds: ApnsCredentials, input: ApnsSendInput): Promise<SendOutcome> {
    let jwt: string;
    try {
        jwt = await getApnsJwt(creds);
    } catch (e) {
        return { kind: "config_error", reason: `apns_jwt_signing_failed: ${stringifyError(e)}` };
    }

    const url = `${APNS_HOST}/3/device/${input.deviceToken}`;
    const payload = {
        aps: {
            alert: {
                title: APNS_NOTIFICATION_TITLE,
                body: input.body,
            },
            sound: "default",
        },
        // Phase 24.5 — top-level `data` dict carries the deep-link
        // route. The `aps` key is reserved for system fields per
        // Apple's APNs payload spec; custom keys live alongside it
        // and are delivered in `userInfo` to UNUserNotificationCenter
        // delegates. Symmetric with FCM's `message.data` field
        // (fcm.ts) so the iOS / Android handlers each pluck `route`
        // out of the same shape.
        data: {
            route: input.route,
        },
    };

    let response: Response;
    try {
        response = await fetch(url, {
            method: "POST",
            headers: {
                authorization: `bearer ${jwt}`,
                "apns-topic": APNS_TOPIC,
                "apns-push-type": "alert",
                "content-type": "application/json",
            },
            body: JSON.stringify(payload),
        });
    } catch (e) {
        return { kind: "transient_error", reason: `apns_fetch_failed: ${stringifyError(e)}` };
    }

    if (response.status === 200) {
        // Drain body so the connection can return to the HTTP/2 pool.
        await response.body?.cancel();
        return { kind: "success" };
    }

    let reason: string | null = null;
    try {
        const errorBody = (await response.json()) as { reason?: string };
        reason = errorBody.reason ?? null;
    } catch {
        // Non-JSON body (5xx HTML pages from edge proxies, e.g. during
        // APNs outage). Leave reason null — the classifier handles it.
    }

    return classifyApnsResponse(response.status, reason);
}

// ---------------------------------------------------------------------
// Response classification (ADR-018 §3.4)
// ---------------------------------------------------------------------

const APNS_DELETE_REASONS = new Set<string>([
    "BadDeviceToken",
    "DeviceTokenNotForTopic",
    "Unregistered",
]);

const APNS_CONFIG_ERROR_REASONS = new Set<string>([
    "BadCertificate",
    "BadCertificateEnvironment",
    "ExpiredProviderToken",
    "Forbidden",
    "InvalidProviderToken",
    "MissingProviderToken",
]);

/**
 * Classify an APNs response. Defensive against unknown reason codes —
 * unknowns route to `transient_error` (NEVER delete a token on an
 * unrecognized signal; ADR-018 §3.4 fail-safe).
 */
export function classifyApnsResponse(httpStatus: number, reason: string | null): SendOutcome {
    if (httpStatus === 200) return { kind: "success" };
    if (httpStatus === 410) {
        // Apple emits 410 with reason "Unregistered" for retired tokens.
        return { kind: "delete_token", reason: reason ?? "apns_410" };
    }
    if (reason && APNS_DELETE_REASONS.has(reason)) {
        return { kind: "delete_token", reason };
    }
    if (reason && APNS_CONFIG_ERROR_REASONS.has(reason)) {
        return { kind: "config_error", reason };
    }
    if (httpStatus >= 500 || httpStatus === 429) {
        return { kind: "transient_error", reason: reason ?? `apns_${httpStatus}` };
    }
    if (httpStatus >= 400 && httpStatus < 500) {
        // 400-class without a known token-invalid reason: Edge Function
        // bug or APNs payload-shape change. Do NOT delete.
        return { kind: "transient_error", reason: reason ?? `apns_${httpStatus}` };
    }
    // Catch-all (e.g. 1xx, 3xx — should not happen on APNs).
    return { kind: "transient_error", reason: reason ?? `apns_${httpStatus}` };
}

// ---------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------

function stringifyError(e: unknown): string {
    if (e instanceof Error) return e.message;
    return String(e);
}
