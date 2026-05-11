// Phase 39 W5 (ADR-020 §2) — GitHub App authentication + Issue creation.
//
// Three-step authentication sequence per the GitHub Apps spec:
//   1. JWT sign with the App's RS256 private key (PEM), claims:
//      iat=now-60, exp=now+540, iss=APP_ID.
//   2. POST /app/installations/<INSTALLATION_ID>/access_tokens with
//      Authorization: Bearer <JWT> — exchanges for a 1-hour scoped
//      installation token.
//   3. POST /repos/<owner>/<repo>/issues with Authorization: Bearer
//      <installation_token> — creates the Issue.
//
// Both JWT and installation token are cached per Edge Function instance.
// JWT TTL is 9 min (GitHub spec caps at 10); installation token TTL is
// 60 min (GitHub-issued, we refresh at 55 min).
//
// PEM handling: GitHub's downloaded .pem is PKCS#1 RSA private key
// (header "-----BEGIN RSA PRIVATE KEY-----"). WebCrypto's importKey
// accepts only PKCS#8 ("-----BEGIN PRIVATE KEY-----"), so we convert
// inline by wrapping the PKCS#1 body bytes with the 26-byte PKCS#8
// ASN.1 prefix for RSA. This is a pure-format conversion — no key
// material is altered.

import { create as createJwt, getNumericDate } from "@zaubrik/djwt";

// ---------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------

const GITHUB_API_HOST = "https://api.github.com";

/** GitHub spec caps the App JWT at 10 minutes; we mint at 9 to keep
 * margin for clock skew across the Supabase edge → GitHub leg. */
const APP_JWT_TTL_SECONDS = 9 * 60;

/** Refresh the cached JWT 1 minute before expiry. */
const APP_JWT_REFRESH_MARGIN_MS = 60 * 1000;

/** Installation tokens are GitHub-issued at 60-minute lifetime. We
 * refresh 5 minutes before expiry for safety against in-flight delay. */
const INSTALLATION_TOKEN_REFRESH_MARGIN_MS = 5 * 60 * 1000;

const GITHUB_OWNER = "b150005";
const GITHUB_REPO = "skeinly";

const USER_AGENT = "Skeinly-Feedback/1.0";

// ---------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------

export interface GithubAppCredentials {
    /** Numeric App ID from GitHub App settings page. */
    appId: string;
    /** Numeric Installation ID from the post-install URL
     *  (`/settings/installations/<id>`). */
    installationId: string;
    /** Full PEM body of the .pem downloaded from the App settings page.
     *  Includes header / footer lines and base64 body. PKCS#1 ("RSA
     *  PRIVATE KEY") OR PKCS#8 ("PRIVATE KEY") both accepted. */
    privateKeyPem: string;
}

export interface CreateIssueInput {
    title: string;
    body: string;
    labels: readonly string[];
}

export type CreateIssueResult =
    | { kind: "success"; issueNumber: number; htmlUrl: string }
    | { kind: "auth_failed"; message: string }
    | { kind: "validation_failed"; message: string }
    | { kind: "api_failed"; message: string };

// ---------------------------------------------------------------------
// Per-instance caches
// ---------------------------------------------------------------------

interface JwtCacheEntry {
    value: string;
    expiresAt: number;
}

interface InstallationTokenCacheEntry {
    value: string;
    expiresAt: number;
}

let appJwtCache: JwtCacheEntry | null = null;
let installationTokenCache: InstallationTokenCacheEntry | null = null;

/** Test-only. */
export function _resetCachesForTests(): void {
    appJwtCache = null;
    installationTokenCache = null;
}

// ---------------------------------------------------------------------
// JWT signing
// ---------------------------------------------------------------------

/**
 * Mint or reuse the per-instance App JWT. Cached across calls within
 * the same Edge Function instance, refreshed when within
 * `APP_JWT_REFRESH_MARGIN_MS` of expiry. RS256 algorithm per the
 * GitHub Apps spec.
 */
export async function getAppJwt(creds: GithubAppCredentials): Promise<string> {
    const now = Date.now();
    if (appJwtCache && appJwtCache.expiresAt - now > APP_JWT_REFRESH_MARGIN_MS) {
        return appJwtCache.value;
    }

    const privateKey = await importRsaPrivateKey(creds.privateKeyPem);
    const header = { alg: "RS256" as const, typ: "JWT" };
    const payload = {
        // GitHub spec: iat allowed up to 60s in the past to absorb
        // clock skew. We use that margin defensively.
        iat: getNumericDate(-60),
        exp: getNumericDate(APP_JWT_TTL_SECONDS),
        iss: creds.appId,
    };

    const jwt = await createJwt(header, payload, privateKey);
    appJwtCache = {
        value: jwt,
        expiresAt: now + APP_JWT_TTL_SECONDS * 1000,
    };
    return jwt;
}

/**
 * Convert the GitHub-supplied PEM (typically PKCS#1) into a WebCrypto
 * RSASSA-PKCS1-v1_5 / SHA-256 CryptoKey suitable for djwt's create().
 *
 * The PEM may already be PKCS#8 (some operators convert before storing
 * the secret); the function detects header form and routes accordingly.
 */
async function importRsaPrivateKey(pem: string): Promise<CryptoKey> {
    const trimmed = pem.trim();
    const isPkcs1 = trimmed.includes("-----BEGIN RSA PRIVATE KEY-----");
    const isPkcs8 = trimmed.includes("-----BEGIN PRIVATE KEY-----");
    if (!isPkcs1 && !isPkcs8) {
        throw new Error("private_key_pem is not in a recognised PEM format");
    }

    const bodyB64 = trimmed
        .replace(/-----BEGIN (RSA )?PRIVATE KEY-----/g, "")
        .replace(/-----END (RSA )?PRIVATE KEY-----/g, "")
        .replace(/\s+/g, "");
    const bodyBytes = base64DecodeToBuffer(bodyB64);
    const pkcs8Buffer = isPkcs8 ? bodyBytes : wrapPkcs1AsPkcs8(bodyBytes);

    return await crypto.subtle.importKey(
        "pkcs8",
        pkcs8Buffer,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["sign"],
    );
}

/**
 * Wrap a raw PKCS#1 RSAPrivateKey blob with the static PKCS#8
 * `PrivateKeyInfo` prefix so WebCrypto can ingest it.
 *
 * PKCS#8 ASN.1 layout:
 *   SEQUENCE {
 *     INTEGER 0,                              -- version
 *     SEQUENCE {                              -- AlgorithmIdentifier
 *       OBJECT IDENTIFIER 1.2.840.113549.1.1.1, -- rsaEncryption
 *       NULL
 *     },
 *     OCTET STRING { ...PKCS#1 RSAPrivateKey bytes... }
 *   }
 *
 * The prefix bytes are static for RSA. The two length fields (outer
 * SEQUENCE and OCTET STRING) are encoded with the long-form length
 * indicator and contain the PKCS#1 body length expressed as 2 or 4
 * bytes depending on size — GitHub-issued keys are 2048-bit, producing
 * a PKCS#1 body of ~1192 bytes which always needs the 2-byte length
 * form. We compute both lengths defensively to handle 4096-bit keys.
 */
function wrapPkcs1AsPkcs8(pkcs1: ArrayBuffer): ArrayBuffer {
    const pkcs1Bytes = new Uint8Array(pkcs1);
    // AlgorithmIdentifier for rsaEncryption (OID 1.2.840.113549.1.1.1)
    //  + NULL parameters: SEQUENCE(13) { OID(9) ..., NULL }
    const algIdentifier = new Uint8Array([
        0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
    ]);
    // version INTEGER 0
    const version = new Uint8Array([0x02, 0x01, 0x00]);
    // OCTET STRING wrapping the PKCS#1 body
    const octetStringHeader = encodeAsn1TlvHeader(0x04, pkcs1Bytes.length);
    // Outer SEQUENCE wrapping {version, algIdentifier, octetString}
    const innerLength =
        version.length + algIdentifier.length + octetStringHeader.length + pkcs1Bytes.length;
    const outerSeqHeader = encodeAsn1TlvHeader(0x30, innerLength);

    const total = outerSeqHeader.length + innerLength;
    const out = new Uint8Array(total);
    let offset = 0;
    out.set(outerSeqHeader, offset);
    offset += outerSeqHeader.length;
    out.set(version, offset);
    offset += version.length;
    out.set(algIdentifier, offset);
    offset += algIdentifier.length;
    out.set(octetStringHeader, offset);
    offset += octetStringHeader.length;
    out.set(pkcs1Bytes, offset);
    return out.buffer;
}

/**
 * Encode the ASN.1 tag + length prefix for a TLV. Length encoding is
 * short-form (single byte) for <128 byte payloads, long-form
 * (0x80|len_octets followed by big-endian length) otherwise.
 */
function encodeAsn1TlvHeader(tag: number, contentLength: number): Uint8Array {
    if (contentLength < 0x80) {
        return new Uint8Array([tag, contentLength]);
    }
    const lengthBytes: number[] = [];
    let n = contentLength;
    while (n > 0) {
        lengthBytes.unshift(n & 0xff);
        n >>>= 8;
    }
    return new Uint8Array([tag, 0x80 | lengthBytes.length, ...lengthBytes]);
}

function base64DecodeToBuffer(b64: string): ArrayBuffer {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
        out[i] = bin.charCodeAt(i);
    }
    return out.buffer;
}

// ---------------------------------------------------------------------
// Installation access token exchange
// ---------------------------------------------------------------------

/**
 * Exchange the App JWT for a 1-hour installation access token. Cached
 * per-instance with a 5-minute refresh margin. On 401/403, the cache
 * is cleared so a subsequent call re-runs the exchange from scratch
 * (defensive against transient auth flaps and rotated App keys).
 */
export async function getInstallationToken(creds: GithubAppCredentials): Promise<string> {
    const now = Date.now();
    if (
        installationTokenCache &&
        installationTokenCache.expiresAt - now > INSTALLATION_TOKEN_REFRESH_MARGIN_MS
    ) {
        return installationTokenCache.value;
    }

    const jwt = await getAppJwt(creds);
    const url = `${GITHUB_API_HOST}/app/installations/${creds.installationId}/access_tokens`;
    const resp = await fetch(url, {
        method: "POST",
        headers: {
            Authorization: `Bearer ${jwt}`,
            Accept: "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": USER_AGENT,
        },
    });

    if (resp.status === 401 || resp.status === 403) {
        // Likely cause: App ID mismatch, key revoked, or installation
        // uninstalled. Clear both caches so the next attempt starts
        // fresh — the operator may have rotated the App secrets.
        appJwtCache = null;
        installationTokenCache = null;
        const text = await resp.text();
        throw new GithubAuthError(
            `installation_token_exchange_failed: HTTP ${resp.status} — ${truncate(text, 200)}`,
        );
    }
    if (!resp.ok) {
        const text = await resp.text();
        throw new GithubApiError(
            `installation_token_exchange_failed: HTTP ${resp.status} — ${truncate(text, 200)}`,
        );
    }

    const payload = (await resp.json()) as { token: string; expires_at: string };
    if (!payload.token || !payload.expires_at) {
        throw new GithubApiError("installation_token response missing token or expires_at");
    }

    const expiresAtMs = Date.parse(payload.expires_at);
    if (Number.isNaN(expiresAtMs)) {
        throw new GithubApiError("installation_token expires_at is not a valid ISO-8601 timestamp");
    }

    installationTokenCache = {
        value: payload.token,
        expiresAt: expiresAtMs,
    };
    return payload.token;
}

// ---------------------------------------------------------------------
// Issue creation
// ---------------------------------------------------------------------

/**
 * Create an Issue on `b150005/skeinly` using the cached installation
 * token. Returns a discriminated union covering the four ADR-020 §2
 * outcome shapes (success / auth_failed / validation_failed /
 * api_failed). Caller maps to the public error envelope.
 */
export async function createIssue(
    creds: GithubAppCredentials,
    input: CreateIssueInput,
): Promise<CreateIssueResult> {
    let installationToken: string;
    try {
        installationToken = await getInstallationToken(creds);
    } catch (error: unknown) {
        if (error instanceof GithubAuthError) {
            return { kind: "auth_failed", message: error.message };
        }
        if (error instanceof GithubApiError) {
            return { kind: "api_failed", message: error.message };
        }
        return { kind: "api_failed", message: errorMessage(error) };
    }

    const url = `${GITHUB_API_HOST}/repos/${GITHUB_OWNER}/${GITHUB_REPO}/issues`;
    let resp: Response;
    try {
        resp = await fetch(url, {
            method: "POST",
            headers: {
                Authorization: `Bearer ${installationToken}`,
                Accept: "application/vnd.github+json",
                "Content-Type": "application/json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": USER_AGENT,
            },
            body: JSON.stringify({
                title: input.title,
                body: input.body,
                labels: input.labels,
            }),
        });
    } catch (error: unknown) {
        return { kind: "api_failed", message: errorMessage(error) };
    }

    if (resp.status === 401 || resp.status === 403) {
        // Installation token was rejected — likely revoked since
        // issuance. Clear cache so the next call re-issues.
        installationTokenCache = null;
        const text = await resp.text();
        return {
            kind: "auth_failed",
            message: `issue_create_unauthorized: HTTP ${resp.status} — ${truncate(text, 200)}`,
        };
    }
    if (resp.status === 422) {
        const text = await resp.text();
        return {
            kind: "validation_failed",
            message: `issue_create_invalid: ${truncate(text, 400)}`,
        };
    }
    if (!resp.ok) {
        const text = await resp.text();
        return {
            kind: "api_failed",
            message: `issue_create_failed: HTTP ${resp.status} — ${truncate(text, 200)}`,
        };
    }

    let payload: { number?: number; html_url?: string };
    try {
        payload = (await resp.json()) as { number?: number; html_url?: string };
    } catch (error: unknown) {
        return {
            kind: "api_failed",
            message: `issue_create_response_unparseable: ${errorMessage(error)}`,
        };
    }

    if (typeof payload.number !== "number" || typeof payload.html_url !== "string") {
        return { kind: "api_failed", message: "issue_create_response missing number or html_url" };
    }
    return { kind: "success", issueNumber: payload.number, htmlUrl: payload.html_url };
}

// ---------------------------------------------------------------------
// Error classes
// ---------------------------------------------------------------------

export class GithubAuthError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "GithubAuthError";
    }
}

export class GithubApiError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "GithubApiError";
    }
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

function errorMessage(error: unknown): string {
    if (error instanceof Error) {
        return error.message;
    }
    return String(error);
}

function truncate(s: string, max: number): string {
    if (s.length <= max) return s;
    return `${s.slice(0, max)}…`;
}
