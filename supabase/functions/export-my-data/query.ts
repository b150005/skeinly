// Pre-Phase-40 A20 Option B (docs/en/ops/data-export-sop.md §Scope
// deferrals) — pure bundle composer for the export-my-data Edge
// Function.
//
// This module owns the GDPR Article 20 / CCPA "right to know" data
// shape. It is the in-app, server-side equivalent of the operator SOP's
// step-4 SQL UNION + step-5 auth.users note + step-6/7/8 "not included"
// pointers. Kept as a pure function over a small [DataExportPort] so the
// table-scoping predicates + bundle shape are unit-tested in
// query.test.ts WITHOUT standing up supabase-js / Deno.serve.
//
// SECURITY INVARIANT (the entire reason this is a separate module):
// every row set is fetched scoped to a single `userId` that
// index.ts derives EXCLUSIVELY from the verified JWT `sub` claim — it
// is NEVER read from the request body. There is no caller-supplied
// identifier anywhere in this file's surface. The port methods take
// `userId` as their only ownership input; an IDOR would require
// index.ts to pass a non-self id, which the JWT-decode path structurally
// prevents (same pattern as submit-ugc-report's `reporterId`).

// ---------------------------------------------------------------------
// Row + port types
// ---------------------------------------------------------------------

/** An opaque table row. We never introspect columns here — the bundle
 *  is a verbatim passthrough of the user's own rows (data portability
 *  means "give the user exactly what we store", not a curated subset). */
export type Row = Record<string, unknown>;

/** Minimal `auth.users` projection (SOP step 5). `raw_user_meta_data`
 *  carries OAuth display name / avatar URL / locale etc. */
export interface AccountRow {
  id: string;
  email: string | null;
  created_at: string | null;
  last_sign_in_at: string | null;
  raw_user_meta_data: Record<string, unknown> | null;
}

/** Storage object metadata (SOP step 4 avatar enumeration). We export
 *  the avatar object *metadata* (name / timestamps / size), NOT the
 *  image bytes — the user already holds the image they uploaded, and
 *  inlining base64 blobs would unbound the response. The `notes`
 *  section documents this so the export is not silently lossy. */
export interface StorageObjectRow {
  name: string;
  created_at: string | null;
  updated_at: string | null;
  last_accessed_at: string | null;
  metadata: Record<string, unknown> | null;
}

/**
 * Data-access port the bundle composer depends on. index.ts implements
 * it over a service-role supabase-js client (service role is required
 * to reach `auth.users` + `storage.objects`; RLS is bypassed, so EVERY
 * method scopes by the JWT-derived `userId` in code — the explicit
 * predicate IS the access control, mirroring the SOP's per-table WHERE).
 */
export interface DataExportPort {
  /** `SELECT * FROM <table> WHERE <column> = <userId>`. */
  selectOwned(table: string, column: string, userId: string): Promise<Row[]>;

  /** `SELECT * FROM <table> WHERE <colA> = <userId> OR <colB> = <userId>`
   *  — the `shares` row predicate (from_user_id OR to_user_id). */
  selectOwnedEither(
    table: string,
    colA: string,
    colB: string,
    userId: string,
  ): Promise<Row[]>;

  /** `SELECT * FROM <table> WHERE <column> IN (<ids>)` — child tables
   *  reachable only through the user's own pattern / project ids
   *  (project_segments, chart_documents, chart_versions,
   *  chart_variations). Empty `ids` MUST short-circuit to `[]` without
   *  issuing a query (`.in("x", [])` is a footgun across clients). */
  selectChildrenIn(
    table: string,
    column: string,
    ids: string[],
  ): Promise<Row[]>;

  /** `auth.admin.getUserById(userId)` projection, or null if absent. */
  getAccount(userId: string): Promise<AccountRow | null>;

  /** `storage.from('avatars').list('<userId>/')` metadata. */
  listAvatars(userId: string): Promise<StorageObjectRow[]>;
}

// ---------------------------------------------------------------------
// Bundle shape
// ---------------------------------------------------------------------

/** Bumped only on a breaking change to the export JSON shape so a
 *  consumer (or a future re-import path) can branch on it. */
export const BUNDLE_SCHEMA_VERSION = 1;

export interface DataExportBundle {
  schema_version: number;
  exported_at: string; // ISO-8601, supplied by the caller (testable)
  user_id: string;
  account: AccountRow | null;
  tables: Record<string, Row[]>;
  storage: { avatars: StorageObjectRow[] };
  /** SOP steps 6–8 — the data Skeinly does NOT directly hold. Static
   *  pointers so the in-app export is not silently incomplete vs. the
   *  operator-fulfilled SOP bundle. `app_user_id` lets the user
   *  self-serve a RevenueCat / Sentry / PostHog request. */
  notes: ExportNotes;
}

export interface ExportNotes {
  bug_reports: string;
  iap_revenuecat: string;
  analytics: string;
  avatars: string;
}

export interface BundleResult {
  bundle: DataExportBundle;
  /** Per-table row counts (+ `_avatars`) for the success UI summary so
   *  the client renders "N records" without parsing the whole bundle. */
  summary: Record<string, number>;
  /** Sum of every `summary` entry. */
  totalRows: number;
}

// ---------------------------------------------------------------------
// Table → ownership-predicate map (the SOP step-4 query, in data form)
// ---------------------------------------------------------------------

/** Tables owned via a direct `<column> = auth.uid()` predicate. */
const DIRECT_OWNED: ReadonlyArray<{ table: string; column: string }> = [
  { table: "profiles", column: "id" },
  { table: "patterns", column: "owner_id" },
  { table: "projects", column: "owner_id" },
  { table: "progress", column: "owner_id" },
  { table: "comments", column: "author_id" },
  { table: "suggestions", column: "author_id" },
  { table: "suggestion_comments", column: "author_id" },
  { table: "activities", column: "actor_id" },
  { table: "device_tokens", column: "user_id" },
  { table: "subscriptions", column: "user_id" },
  { table: "user_symbol_pack_state", column: "user_id" },
  { table: "feedback", column: "user_id" },
];

/** Child tables reachable only through the user's own pattern ids. */
const PATTERN_CHILDREN: ReadonlyArray<{ table: string; column: string }> = [
  { table: "chart_documents", column: "pattern_id" },
  { table: "chart_versions", column: "pattern_id" },
  { table: "chart_variations", column: "pattern_id" },
];

const STATIC_NOTES: ExportNotes = {
  bug_reports:
    "Bug reports submitted via in-app feedback are filed as public " +
    "GitHub Issues on b150005/skeinly by the Skeinly Feedback app and " +
    "are not tied to your Supabase account. Search " +
    "https://github.com/b150005/skeinly/issues for any you recall filing.",
  iap_revenuecat:
    "Subscription transactions live partially in RevenueCat and " +
    "partially in Apple/Google. Your RevenueCat app_user_id equals your " +
    "user_id (above). Request RevenueCat-side records from RevenueCat " +
    "support; Apple/Google IAP history is requestable directly from " +
    "Apple/Google.",
  analytics:
    "If you opted in to crash reporting (Sentry) or product analytics " +
    "(PostHog), those events are keyed to an anonymous per-install " +
    "identifier, not your account. Request them directly from Sentry / " +
    "PostHog if needed.",
  avatars: "The storage.avatars entries are object metadata only. The image " +
    "files themselves are the pictures you uploaded; download them from " +
    "your profile if you need the binaries.",
};

// ---------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------

/**
 * Compose the full export bundle for `userId`.
 *
 * `userId` MUST be the JWT-`sub`-derived caller id (index.ts contract).
 * `nowIso` is injected (not `new Date()`) so query.test.ts can assert a
 * deterministic `exported_at`.
 *
 * Order of operations:
 *  1. account (auth.users projection)
 *  2. direct-owned tables (12) in parallel
 *  3. patterns/projects ids → child tables (4) — sequential after
 *     patterns/projects resolve, since the child predicate needs the
 *     parent id list
 *  4. shares (either-direction predicate)
 *  5. storage avatars listing
 *
 * A per-source failure is NOT swallowed — a partial export is worse
 * than a clear failure for a data-rights request (the user must be able
 * to trust completeness). index.ts maps a thrown error to an
 * EXPORT_FAILED envelope.
 */
export async function buildBundle(
  port: DataExportPort,
  userId: string,
  nowIso: string,
): Promise<BundleResult> {
  const tables: Record<string, Row[]> = {};

  // 1. account
  const account = await port.getAccount(userId);

  // 2. direct-owned (parallel)
  const directResults = await Promise.all(
    DIRECT_OWNED.map((d) => port.selectOwned(d.table, d.column, userId)),
  );
  DIRECT_OWNED.forEach((d, i) => {
    tables[d.table] = directResults[i];
  });

  // 3. child tables via the user's own pattern / project ids
  const patternIds = idsOf(tables["patterns"], "id");
  const projectIds = idsOf(tables["projects"], "id");

  const childResults = await Promise.all([
    ...PATTERN_CHILDREN.map((c) =>
      port.selectChildrenIn(c.table, c.column, patternIds)
    ),
    port.selectChildrenIn("project_segments", "project_id", projectIds),
  ]);
  PATTERN_CHILDREN.forEach((c, i) => {
    tables[c.table] = childResults[i];
  });
  tables["project_segments"] = childResults[PATTERN_CHILDREN.length];

  // 4. shares (either direction)
  tables["shares"] = await port.selectOwnedEither(
    "shares",
    "from_user_id",
    "to_user_id",
    userId,
  );

  // 5. storage avatars
  const avatars = await port.listAvatars(userId);

  const bundle: DataExportBundle = {
    schema_version: BUNDLE_SCHEMA_VERSION,
    exported_at: nowIso,
    user_id: userId,
    account,
    tables,
    storage: { avatars },
    notes: STATIC_NOTES,
  };

  const summary: Record<string, number> = {};
  let totalRows = 0;
  for (const [table, rows] of Object.entries(tables)) {
    summary[table] = rows.length;
    totalRows += rows.length;
  }
  summary["_avatars"] = avatars.length;
  totalRows += avatars.length;

  return { bundle, summary, totalRows };
}

/** Extract a string id column from a row set, dropping non-string /
 *  missing values defensively (a malformed row must not crash the
 *  whole export). */
function idsOf(rows: Row[] | undefined, column: string): string[] {
  if (!rows) return [];
  const out: string[] = [];
  for (const r of rows) {
    const v = r[column];
    if (typeof v === "string" && v.length > 0) out.push(v);
  }
  return out;
}
