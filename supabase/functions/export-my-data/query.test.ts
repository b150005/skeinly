// Pre-Phase-40 A20 Option B — query.ts (bundle composer) test suite.
//
// Coverage: every owned table appears in the bundle scoped to the
// caller id; child tables resolve through the user's own pattern /
// project ids (and short-circuit on empty parent sets); shares uses the
// either-direction predicate; account + avatars wiring; deterministic
// exported_at; summary + totalRows arithmetic; the security invariant
// that NO port call ever receives an id other than the caller's.

import { assert, assertEquals } from "@std/assert";
import {
  type AccountRow,
  buildBundle,
  BUNDLE_SCHEMA_VERSION,
  type DataExportPort,
  type Row,
  type StorageObjectRow,
} from "./query.ts";

const USER = "11111111-1111-1111-1111-111111111111";
const OTHER = "99999999-9999-9999-9999-999999999999";
const NOW = "2026-05-16T12:00:00.000Z";

// ---------------------------------------------------------------------
// In-memory fake port. Seeds a row per table tagged with which id it
// "belongs to" so we can assert scoping. Records every call so we can
// assert the security invariant (only USER is ever passed).
// ---------------------------------------------------------------------

interface PortCall {
  method: string;
  table: string;
  arg: string | string[];
}

class FakePort implements DataExportPort {
  readonly calls: PortCall[] = [];

  constructor(
    private readonly seed: {
      owned?: Record<string, Row[]>;
      children?: Record<string, Row[]>;
      shares?: Row[];
      account?: AccountRow | null;
      avatars?: StorageObjectRow[];
    } = {},
  ) {}

  selectOwned(table: string, column: string, userId: string): Promise<Row[]> {
    this.calls.push({ method: "selectOwned", table, arg: userId });
    // Emulate scoping: only return rows when the predicate value is the
    // caller id (a correct composer never asks for OTHER).
    if (userId !== USER) return Promise.resolve([]);
    return Promise.resolve(this.seed.owned?.[table] ?? []);
  }

  selectOwnedEither(
    table: string,
    _colA: string,
    _colB: string,
    userId: string,
  ): Promise<Row[]> {
    this.calls.push({ method: "selectOwnedEither", table, arg: userId });
    if (userId !== USER) return Promise.resolve([]);
    return Promise.resolve(this.seed.shares ?? []);
  }

  selectChildrenIn(
    table: string,
    _column: string,
    ids: string[],
  ): Promise<Row[]> {
    this.calls.push({ method: "selectChildrenIn", table, arg: ids });
    if (ids.length === 0) return Promise.resolve([]);
    return Promise.resolve(this.seed.children?.[table] ?? []);
  }

  getAccount(userId: string): Promise<AccountRow | null> {
    this.calls.push({ method: "getAccount", table: "auth.users", arg: userId });
    if (userId !== USER) return Promise.resolve(null);
    return Promise.resolve(this.seed.account ?? null);
  }

  listAvatars(userId: string): Promise<StorageObjectRow[]> {
    this.calls.push({ method: "listAvatars", table: "avatars", arg: userId });
    if (userId !== USER) return Promise.resolve([]);
    return Promise.resolve(this.seed.avatars ?? []);
  }
}

// ---------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------

Deno.test("buildBundle stamps schema version, caller id, injected timestamp", async () => {
  const { bundle } = await buildBundle(new FakePort(), USER, NOW);
  assertEquals(bundle.schema_version, BUNDLE_SCHEMA_VERSION);
  assertEquals(bundle.user_id, USER);
  assertEquals(bundle.exported_at, NOW);
});

Deno.test("buildBundle includes all 17 always-present tables even when empty", async () => {
  const { bundle } = await buildBundle(new FakePort(), USER, NOW);
  for (
    const t of [
      "profiles",
      "patterns",
      "projects",
      "progress",
      "comments",
      "suggestions",
      "suggestion_comments",
      "activities",
      "device_tokens",
      "subscriptions",
      "user_symbol_pack_state",
      "feedback",
      "chart_documents",
      "chart_versions",
      "chart_variations",
      "project_segments",
      "shares",
    ]
  ) {
    assert(t in bundle.tables, `expected '${t}' key in bundle.tables`);
    assertEquals(bundle.tables[t], []);
  }
});

Deno.test("buildBundle passes ONLY the caller id to every port method (IDOR invariant)", async () => {
  const port = new FakePort({
    owned: { patterns: [{ id: "p1" }], projects: [{ id: "j1" }] },
  });
  await buildBundle(port, USER, NOW);
  // Every single-id call argument must equal USER. Never OTHER, never
  // anything else.
  for (const c of port.calls) {
    if (typeof c.arg === "string") {
      assertEquals(
        c.arg,
        USER,
        `port.${c.method}(${c.table}) leaked id ${c.arg}`,
      );
    }
  }
  // No call should ever reference OTHER in any form.
  const flat = JSON.stringify(port.calls);
  assert(!flat.includes(OTHER), "a port call referenced a non-caller id");
});

Deno.test("buildBundle resolves child tables through the user's own pattern/project ids", async () => {
  const port = new FakePort({
    owned: {
      patterns: [{ id: "pat-a" }, { id: "pat-b" }],
      projects: [{ id: "proj-1" }],
    },
    children: {
      chart_documents: [{ id: "cd1", pattern_id: "pat-a" }],
      chart_versions: [{ id: "cv1", pattern_id: "pat-b" }],
      chart_variations: [{ id: "cvar1", pattern_id: "pat-a" }],
      project_segments: [{ id: "seg1", project_id: "proj-1" }],
    },
  });
  const { bundle } = await buildBundle(port, USER, NOW);
  assertEquals(bundle.tables["chart_documents"].length, 1);
  assertEquals(bundle.tables["chart_versions"].length, 1);
  assertEquals(bundle.tables["chart_variations"].length, 1);
  assertEquals(bundle.tables["project_segments"].length, 1);

  // The child queries received exactly the parent id lists.
  const childCalls = port.calls.filter((c) => c.method === "selectChildrenIn");
  const cd = childCalls.find((c) => c.table === "chart_documents")!;
  assertEquals(cd.arg, ["pat-a", "pat-b"]);
  const seg = childCalls.find((c) => c.table === "project_segments")!;
  assertEquals(seg.arg, ["proj-1"]);
});

Deno.test("buildBundle short-circuits child queries when the user has no patterns/projects", async () => {
  const port = new FakePort(); // no patterns, no projects
  await buildBundle(port, USER, NOW);
  const childCalls = port.calls.filter((c) => c.method === "selectChildrenIn");
  // All 4 child queries still issued, but each with an empty id list →
  // the port short-circuits to [] (asserted by the empty-table check
  // in the earlier test). Confirm the arg was [].
  assertEquals(childCalls.length, 4);
  for (const c of childCalls) assertEquals(c.arg, []);
});

Deno.test("buildBundle uses the either-direction predicate for shares", async () => {
  const port = new FakePort({
    shares: [
      { id: "s1", from_user_id: USER, to_user_id: OTHER },
      { id: "s2", from_user_id: OTHER, to_user_id: USER },
    ],
  });
  const { bundle } = await buildBundle(port, USER, NOW);
  assertEquals(bundle.tables["shares"].length, 2);
  const shareCall = port.calls.find((c) => c.method === "selectOwnedEither")!;
  assertEquals(shareCall.table, "shares");
  assertEquals(shareCall.arg, USER);
});

Deno.test("buildBundle attaches account projection + avatar metadata + static notes", async () => {
  const account: AccountRow = {
    id: USER,
    email: "knitter@example.com",
    created_at: "2026-01-01T00:00:00Z",
    last_sign_in_at: "2026-05-15T00:00:00Z",
    raw_user_meta_data: { full_name: "A. Knitter" },
  };
  const avatars: StorageObjectRow[] = [
    {
      name: `${USER}/avatar.png`,
      created_at: "2026-02-01T00:00:00Z",
      updated_at: null,
      last_accessed_at: null,
      metadata: { size: 1234 },
    },
  ];
  const { bundle } = await buildBundle(
    new FakePort({ account, avatars }),
    USER,
    NOW,
  );
  assertEquals(bundle.account, account);
  assertEquals(bundle.storage.avatars, avatars);
  assert(bundle.notes.bug_reports.includes("GitHub Issues"));
  assert(bundle.notes.iap_revenuecat.includes("RevenueCat"));
  assert(bundle.notes.analytics.includes("anonymous"));
  assert(bundle.notes.avatars.includes("metadata only"));
});

Deno.test("buildBundle summary counts every table plus _avatars and totals them", async () => {
  const port = new FakePort({
    owned: {
      patterns: [{ id: "p1" }, { id: "p2" }],
      comments: [{ id: "c1" }],
    },
    children: { chart_documents: [{ id: "cd1", pattern_id: "p1" }] },
    shares: [{ id: "s1" }],
    avatars: [
      {
        name: `${USER}/a.png`,
        created_at: null,
        updated_at: null,
        last_accessed_at: null,
        metadata: null,
      },
    ],
  });
  const { summary, totalRows } = await buildBundle(port, USER, NOW);
  assertEquals(summary["patterns"], 2);
  assertEquals(summary["comments"], 1);
  assertEquals(summary["chart_documents"], 1);
  assertEquals(summary["shares"], 1);
  assertEquals(summary["_avatars"], 1);
  // 2 + 1 + 1 + 1 + 1 = 6, every other table is 0.
  assertEquals(totalRows, 6);
});

Deno.test("buildBundle tolerates malformed parent rows without crashing the child predicate", async () => {
  // A pattern row missing `id` (or with a non-string id) must not throw
  // — a single bad row cannot fail an entire data-rights export.
  const port = new FakePort({
    owned: {
      patterns: [{ id: "good" }, { name: "no-id-row" }, { id: 42 }],
    },
    children: { chart_documents: [{ id: "cd1", pattern_id: "good" }] },
  });
  const { bundle } = await buildBundle(port, USER, NOW);
  const cdCall = port.calls.find(
    (c) => c.method === "selectChildrenIn" && c.table === "chart_documents",
  )!;
  // Only the well-formed string id survives.
  assertEquals(cdCall.arg, ["good"]);
  assertEquals(bundle.tables["chart_documents"].length, 1);
});
