// Pre-alpha A1/A5 (ADR-021 §D3) — pure helpers for UGC report shaping.
//
// Separated from the I/O handler in index.ts so the templating + redaction
// logic is independently testable without Supabase/GitHub mocks.

// ---------------------------------------------------------------------
// Closed enums (must match the CHECK constraints on public.ugc_reports
// in migration 031).
// ---------------------------------------------------------------------

export const REASON_CATEGORIES = [
    "spam",
    "harassment",
    "sexual",
    "violence",
    "hate",
    "ip",
    "other",
] as const;

export type ReasonCategory = (typeof REASON_CATEGORIES)[number];

export const TARGET_TYPES = [
    "pattern",
    "comment",
    "suggestion",
    "suggestion_comment",
] as const;

export type TargetType = (typeof TARGET_TYPES)[number];

// ---------------------------------------------------------------------
// Validation limits (must match CHECK constraints in migration 031).
// ---------------------------------------------------------------------

export const MAX_REASON_LENGTH = 2_000;

/** Reason text included in the GitHub Issue body is truncated to this
 *  many characters to prevent oversized Issues from runaway report
 *  text. The full reason is preserved in `public.ugc_reports.reason`. */
export const ISSUE_BODY_REASON_PREVIEW_CHARS = 500;

// ---------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------

export interface SubmitUgcReportInput {
    target_type: TargetType;
    target_id: string; // UUID
    reason: string;
    reason_category: ReasonCategory;
}

export interface IssueTemplateInput {
    /** Authenticated reporter user_id (UUID). Embedded verbatim in the
     *  Issue body — operator resolves to email via Dashboard SQL. */
    reporterId: string;
    /** Primary key of the freshly-inserted row in public.ugc_reports.
     *  Embedded so the operator can navigate from GitHub Issue → DB row
     *  in one SQL query (`SELECT * FROM ugc_reports WHERE id = …`). */
    reportId: string;
    input: SubmitUgcReportInput;
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

/**
 * Validate the raw request body against the ADR-021 §D1 contract.
 * Caller passes through `parsed` from `req.json()` — the function
 * narrows `unknown` to `SubmitUgcReportInput` on the success path.
 *
 * UUID format is checked structurally (8-4-4-4-12 hex) rather than
 * existentially against the database — the DB FK on target_id is
 * deferred to the operator triage step (target_id may refer to a
 * since-deleted row; this is acceptable and captured in `target_id`
 * + `target_type` as evidence). The reporter cannot fabricate a
 * non-existent row reference into a privilege escalation here
 * because the row's authority is `public.ugc_reports`, not
 * `public.<target_type>s`.
 */
export function validateInput(
    parsed: unknown,
): { ok: true; value: SubmitUgcReportInput } | { ok: false; message: string } {
    if (typeof parsed !== "object" || parsed === null) {
        return { ok: false, message: "request body must be a JSON object" };
    }
    const obj = parsed as Record<string, unknown>;

    if (typeof obj.target_type !== "string") {
        return { ok: false, message: "target_type must be a string" };
    }
    if (!isTargetType(obj.target_type)) {
        return {
            ok: false,
            message: `target_type must be one of: ${TARGET_TYPES.join(", ")}`,
        };
    }

    if (typeof obj.target_id !== "string") {
        return { ok: false, message: "target_id must be a string" };
    }
    if (!isUuid(obj.target_id)) {
        return { ok: false, message: "target_id must be a UUID" };
    }

    if (typeof obj.reason !== "string") {
        return { ok: false, message: "reason must be a string" };
    }
    if (obj.reason.length < 1 || obj.reason.length > MAX_REASON_LENGTH) {
        return {
            ok: false,
            message: `reason length must be 1..${MAX_REASON_LENGTH}`,
        };
    }

    if (typeof obj.reason_category !== "string") {
        return { ok: false, message: "reason_category must be a string" };
    }
    if (!isReasonCategory(obj.reason_category)) {
        return {
            ok: false,
            message: `reason_category must be one of: ${REASON_CATEGORIES.join(", ")}`,
        };
    }

    return {
        ok: true,
        value: {
            target_type: obj.target_type,
            target_id: obj.target_id,
            reason: obj.reason,
            reason_category: obj.reason_category,
        },
    };
}

function isTargetType(v: string): v is TargetType {
    return (TARGET_TYPES as readonly string[]).includes(v);
}

function isReasonCategory(v: string): v is ReasonCategory {
    return (REASON_CATEGORIES as readonly string[]).includes(v);
}

/** Structural UUID check — 8-4-4-4-12 lowercase hex. Supabase Auth
 *  issues UUIDs in this canonical form; we accept the canonical form
 *  only to keep the DB CHECK aligned with what reaches the wire. */
export function isUuid(v: string): boolean {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
}

/** Truncate the user-submitted reason text to a fixed preview length
 *  for embedding in the GitHub Issue body, with a trailing length
 *  annotation so the operator knows how much was elided. */
export function redactReason(reason: string): string {
    if (reason.length <= ISSUE_BODY_REASON_PREVIEW_CHARS) {
        return reason;
    }
    const head = reason.slice(0, ISSUE_BODY_REASON_PREVIEW_CHARS);
    return `${head}\n\n…(truncated, full length: ${reason.length} chars)`;
}

/** Compose the GitHub Issue title — short enough to fit the GitHub
 *  256-char title limit comfortably. Format chosen for operator-side
 *  filter readability ("UGC report" prefix + category + target_type). */
export function buildIssueTitle(input: SubmitUgcReportInput): string {
    return `[UGC report] ${input.reason_category} — ${input.target_type}`;
}

/**
 * Compose the GitHub Issue body — markdown formatted so the operator
 * triage SOP can grep for headings reliably. Includes:
 *
 *   - reporter user_id (UUID; resolve to email via Dashboard SQL)
 *   - target_type + target_id (the row the report names)
 *   - reason_category (closed enum)
 *   - reason preview (truncated; full body in DB)
 *   - report_id (PK of the public.ugc_reports row)
 *
 * Reporter email is NOT denormalized into the Issue. The operator MUST
 * resolve the UUID to email via the Dashboard before any direct contact
 * (privacy: GitHub Issue audit-log retention is longer than auth.users
 * email lifecycle, so the email gets a tighter lifecycle bound by
 * staying out of Issues).
 */
export function buildIssueBody(args: IssueTemplateInput): string {
    const { reporterId, reportId, input } = args;
    const preview = redactReason(input.reason);
    return [
        `## UGC report`,
        ``,
        `**Report ID**: \`${reportId}\``,
        `**Reporter**: \`${reporterId}\``,
        `**Target**: \`${input.target_type}\` / \`${input.target_id}\``,
        `**Reason category**: \`${input.reason_category}\``,
        ``,
        `### Reason`,
        ``,
        preview,
        ``,
        `---`,
        ``,
        `Operator triage: see [docs/en/ops/ugc-moderation-sop.md](https://github.com/b150005/skeinly/blob/main/docs/en/ops/ugc-moderation-sop.md).`,
        ``,
        `Resolve target_id to its row in the Dashboard SQL Editor:`,
        `\`\`\`sql`,
        `SELECT * FROM public.${input.target_type}s WHERE id = '${input.target_id}';`,
        `\`\`\``,
        ``,
        `When resolved, UPDATE public.ugc_reports SET state = 'resolved_remove' | 'resolved_keep' | 'dismissed', operator_notes = '…', resolved_at = now() WHERE id = '${reportId}';`,
    ].join("\n");
}
