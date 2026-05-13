// Pre-alpha A1/A5 — mapping.ts (pure helpers) test suite.
//
// Coverage: closed-enum admission, UUID format gate, reason length
// gate, reason redaction at the 500-char threshold, Issue title +
// body templating.

import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import {
  buildIssueBody,
  buildIssueTitle,
  ISSUE_BODY_REASON_PREVIEW_CHARS,
  isUuid,
  MAX_REASON_LENGTH,
  REASON_CATEGORIES,
  redactReason,
  TARGET_TYPES,
  validateInput,
} from "./mapping.ts";

// ---------------------------------------------------------------------
// Closed-enum guards
// ---------------------------------------------------------------------

Deno.test("REASON_CATEGORIES matches DB CHECK constraint (migration 031)", () => {
  // Must stay in sync with public.ugc_reports.reason_category CHECK.
  assertEquals(REASON_CATEGORIES, [
    "spam",
    "harassment",
    "sexual",
    "violence",
    "hate",
    "ip",
    "other",
  ]);
});

Deno.test("TARGET_TYPES matches DB CHECK constraint (migration 031)", () => {
  assertEquals(TARGET_TYPES, [
    "pattern",
    "comment",
    "suggestion",
    "suggestion_comment",
  ]);
});

// ---------------------------------------------------------------------
// validateInput
// ---------------------------------------------------------------------

const validUuid = "12345678-1234-1234-1234-123456789abc";

Deno.test("validateInput rejects non-object", () => {
  const r = validateInput("not an object");
  assert(!r.ok);
});

Deno.test("validateInput rejects null", () => {
  const r = validateInput(null);
  assert(!r.ok);
});

Deno.test("validateInput rejects unknown target_type", () => {
  const r = validateInput({
    target_type: "pattern_typo",
    target_id: validUuid,
    reason: "x",
    reason_category: "spam",
  });
  assert(!r.ok);
  if (!r.ok) assertStringIncludes(r.message, "target_type");
});

Deno.test("validateInput accepts every target_type", () => {
  for (const t of TARGET_TYPES) {
    const r = validateInput({
      target_type: t,
      target_id: validUuid,
      reason: "x",
      reason_category: "spam",
    });
    assert(r.ok, `target_type ${t} should pass`);
  }
});

Deno.test("validateInput rejects unknown reason_category", () => {
  const r = validateInput({
    target_type: "pattern",
    target_id: validUuid,
    reason: "x",
    reason_category: "shoplifting",
  });
  assert(!r.ok);
});

Deno.test("validateInput accepts every reason_category", () => {
  for (const c of REASON_CATEGORIES) {
    const r = validateInput({
      target_type: "pattern",
      target_id: validUuid,
      reason: "x",
      reason_category: c,
    });
    assert(r.ok, `reason_category ${c} should pass`);
  }
});

Deno.test("validateInput rejects non-UUID target_id", () => {
  const r = validateInput({
    target_type: "pattern",
    target_id: "not-a-uuid",
    reason: "x",
    reason_category: "spam",
  });
  assert(!r.ok);
  if (!r.ok) assertStringIncludes(r.message, "UUID");
});

Deno.test("validateInput rejects empty reason", () => {
  const r = validateInput({
    target_type: "pattern",
    target_id: validUuid,
    reason: "",
    reason_category: "spam",
  });
  assert(!r.ok);
});

Deno.test("validateInput rejects oversized reason (>2000 chars)", () => {
  const r = validateInput({
    target_type: "pattern",
    target_id: validUuid,
    reason: "x".repeat(MAX_REASON_LENGTH + 1),
    reason_category: "spam",
  });
  assert(!r.ok);
});

Deno.test("validateInput accepts exactly MAX_REASON_LENGTH chars", () => {
  const r = validateInput({
    target_type: "pattern",
    target_id: validUuid,
    reason: "x".repeat(MAX_REASON_LENGTH),
    reason_category: "spam",
  });
  assert(r.ok);
});

// ---------------------------------------------------------------------
// isUuid
// ---------------------------------------------------------------------

Deno.test("isUuid accepts canonical lowercase form", () => {
  assert(isUuid("a1b2c3d4-1234-5678-9abc-def012345678"));
});

Deno.test("isUuid accepts uppercase hex", () => {
  assert(isUuid("A1B2C3D4-1234-5678-9ABC-DEF012345678"));
});

Deno.test("isUuid rejects missing dashes", () => {
  assert(!isUuid("a1b2c3d4123456789abcdef012345678"));
});

Deno.test("isUuid rejects extra characters", () => {
  assert(!isUuid("a1b2c3d4-1234-5678-9abc-def012345678-extra"));
});

// ---------------------------------------------------------------------
// redactReason
// ---------------------------------------------------------------------

Deno.test("redactReason passes through short text unchanged", () => {
  const short = "spam pattern";
  assertEquals(redactReason(short), short);
});

Deno.test("redactReason passes through text at the cap exactly", () => {
  const exact = "x".repeat(ISSUE_BODY_REASON_PREVIEW_CHARS);
  assertEquals(redactReason(exact), exact);
});

Deno.test("redactReason truncates text past the cap and annotates length", () => {
  const long = "x".repeat(ISSUE_BODY_REASON_PREVIEW_CHARS + 100);
  const out = redactReason(long);
  assertStringIncludes(out, "truncated");
  assertStringIncludes(out, String(long.length));
  // First 500 chars preserved verbatim:
  assertEquals(
    out.slice(0, ISSUE_BODY_REASON_PREVIEW_CHARS),
    "x".repeat(ISSUE_BODY_REASON_PREVIEW_CHARS),
  );
});

// ---------------------------------------------------------------------
// buildIssueTitle
// ---------------------------------------------------------------------

Deno.test("buildIssueTitle composes category + target_type", () => {
  const title = buildIssueTitle({
    target_type: "comment",
    target_id: validUuid,
    reason: "x",
    reason_category: "harassment",
  });
  assertEquals(title, "[UGC report] harassment — comment");
});

Deno.test("buildIssueTitle stays under 256 char limit even with longest enums", () => {
  const longest = buildIssueTitle({
    target_type: "suggestion_comment",
    target_id: validUuid,
    reason: "x",
    reason_category: "harassment",
  });
  assert(longest.length < 256);
});

// ---------------------------------------------------------------------
// buildIssueBody
// ---------------------------------------------------------------------

Deno.test("buildIssueBody embeds reporter_id + report_id + target", () => {
  const body = buildIssueBody({
    reporterId: "abcd1234-1111-2222-3333-444455556666",
    reportId: "9999aaaa-bbbb-cccc-dddd-eeeeffff0000",
    input: {
      target_type: "pattern",
      target_id: validUuid,
      reason: "This pattern is offensive.",
      reason_category: "hate",
    },
  });
  assertStringIncludes(body, "abcd1234-1111-2222-3333-444455556666");
  assertStringIncludes(body, "9999aaaa-bbbb-cccc-dddd-eeeeffff0000");
  assertStringIncludes(body, validUuid);
  assertStringIncludes(body, "hate");
  assertStringIncludes(body, "pattern");
});

Deno.test("buildIssueBody includes reason text inline (when under cap)", () => {
  const body = buildIssueBody({
    reporterId: "abcd1234-1111-2222-3333-444455556666",
    reportId: "9999aaaa-bbbb-cccc-dddd-eeeeffff0000",
    input: {
      target_type: "pattern",
      target_id: validUuid,
      reason: "Reason text here.",
      reason_category: "spam",
    },
  });
  assertStringIncludes(body, "Reason text here.");
});

Deno.test("buildIssueBody truncates reason past cap", () => {
  const longReason = "x".repeat(ISSUE_BODY_REASON_PREVIEW_CHARS + 200);
  const body = buildIssueBody({
    reporterId: "abcd1234-1111-2222-3333-444455556666",
    reportId: "9999aaaa-bbbb-cccc-dddd-eeeeffff0000",
    input: {
      target_type: "pattern",
      target_id: validUuid,
      reason: longReason,
      reason_category: "spam",
    },
  });
  assertStringIncludes(body, "truncated");
});

Deno.test("buildIssueBody references SOP runbook URL", () => {
  const body = buildIssueBody({
    reporterId: "abcd1234-1111-2222-3333-444455556666",
    reportId: "9999aaaa-bbbb-cccc-dddd-eeeeffff0000",
    input: {
      target_type: "pattern",
      target_id: validUuid,
      reason: "x",
      reason_category: "spam",
    },
  });
  assertStringIncludes(body, "docs/en/ops/ugc-moderation-sop.md");
});

Deno.test("buildIssueBody emits a Dashboard SQL stub referencing the pluralized table", () => {
  // target_type=comment → public.comments
  const body = buildIssueBody({
    reporterId: "abcd1234-1111-2222-3333-444455556666",
    reportId: "9999aaaa-bbbb-cccc-dddd-eeeeffff0000",
    input: {
      target_type: "comment",
      target_id: validUuid,
      reason: "x",
      reason_category: "spam",
    },
  });
  assertStringIncludes(body, "SELECT * FROM public.comments WHERE id =");
});
