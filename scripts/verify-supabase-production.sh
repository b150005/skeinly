#!/usr/bin/env bash
# Verify Supabase production deployment for Knit Note
# Usage: ./scripts/verify-supabase-production.sh
# Requires: supabase CLI authenticated and linked to knit-note project

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'
PASS=0
FAIL=0

# Global temp file tracker for signal cleanup
_QUERY_TMPFILE=""
trap 'rm -f "$_QUERY_TMPFILE"' EXIT INT TERM

query() {
    _QUERY_TMPFILE=$(mktemp)
    local result
    result=$(supabase db query --linked "$1" -o json 2>"$_QUERY_TMPFILE") || {
        local rc=$?
        echo -e "${RED}query failed:${NC}" >&2
        cat "$_QUERY_TMPFILE" >&2
        rm -f "$_QUERY_TMPFILE"
        _QUERY_TMPFILE=""
        return $rc
    }
    rm -f "$_QUERY_TMPFILE"
    _QUERY_TMPFILE=""
    echo "$result" | { grep -v '"boundary"' || true; } | { grep -v '"warning"' || true; }
}

echo "=== Knit Note — Supabase Production Verification ==="
echo ""

# Pre-flight: verify CLI is linked and can reach the database
echo "0. Connectivity"
if ! supabase db query --linked "SELECT 1;" -o json >/dev/null 2>&1; then
    echo -e "  ${RED}✗${NC} Cannot reach linked Supabase project."
    echo -e "  ${YELLOW}Hint:${NC} Run 'supabase link' and ensure you are authenticated."
    exit 1
fi
echo -e "  ${GREEN}✓${NC} Connected to linked project"
echo ""

# 1. Migration state — verify via DB query instead of parsing CLI table output
echo "1. Migrations"
# SAFETY: values are hardcoded constants — never derive from external input
EXPECTED_MIGRATIONS="001 002 003 004 005 006 007 008"
EXPECTED_COUNT=$(echo "$EXPECTED_MIGRATIONS" | wc -w | tr -d ' ')
APPLIED=0
MISSING=""
for ver in $EXPECTED_MIGRATIONS; do
    result=$(query "SELECT 1 FROM supabase_migrations.schema_migrations WHERE version = '$ver';")
    if echo "$result" | grep -q '"?column?"'; then
        ((APPLIED++))
    else
        MISSING="$MISSING $ver"
    fi
done
if [ "$APPLIED" -eq "$EXPECTED_COUNT" ]; then
    echo -e "  ${GREEN}✓${NC} All $EXPECTED_COUNT migrations applied"
    ((PASS++))
else
    echo -e "  ${RED}✗${NC} $APPLIED of $EXPECTED_COUNT applied, missing:$MISSING"
    ((FAIL++))
fi

# 2. Tables
echo "2. Tables"
# SAFETY: values are hardcoded constants — never derive from external input
for table in profiles projects progress patterns shares comments activities; do
    result=$(query "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='$table';")
    if echo "$result" | grep -q '"?column?"'; then
        echo -e "  ${GREEN}✓${NC} $table"
        ((PASS++))
    else
        echo -e "  ${RED}✗${NC} $table MISSING"
        ((FAIL++))
    fi
done

# 3. Functions
echo "3. Functions"
# SAFETY: values are hardcoded constants — never derive from external input
for func in handle_new_user update_updated_at set_progress_owner_id delete_own_account; do
    result=$(query "SELECT 1 FROM pg_proc WHERE proname='$func' AND pronamespace='public'::regnamespace;")
    if echo "$result" | grep -q '"?column?"'; then
        echo -e "  ${GREEN}✓${NC} $func()"
        ((PASS++))
    else
        echo -e "  ${RED}✗${NC} $func() MISSING"
        ((FAIL++))
    fi
done

# 4. SECURITY DEFINER on delete_own_account
echo "4. Security"
result=$(query "SELECT prosecdef FROM pg_proc WHERE proname='delete_own_account' AND pronamespace='public'::regnamespace;")
if echo "$result" | grep -q '"prosecdef": true'; then
    echo -e "  ${GREEN}✓${NC} delete_own_account is SECURITY DEFINER"
    ((PASS++))
else
    echo -e "  ${RED}✗${NC} delete_own_account NOT SECURITY DEFINER"
    ((FAIL++))
fi

# 5. Storage bucket
echo "5. Storage"
result=$(query "SELECT 1 FROM storage.buckets WHERE id='chart-images' AND public=false;")
if echo "$result" | grep -q '"?column?"'; then
    echo -e "  ${GREEN}✓${NC} chart-images bucket (private)"
    ((PASS++))
else
    echo -e "  ${RED}✗${NC} chart-images bucket MISSING or public"
    ((FAIL++))
fi

STORAGE_POLICIES=$(query "SELECT count(*) as cnt FROM pg_policies WHERE schemaname='storage' AND tablename='objects';")
POLICY_COUNT=$(echo "$STORAGE_POLICIES" | grep -oE '"cnt": [0-9]+' | grep -oE '[0-9]+' || echo "0")
if [[ "$POLICY_COUNT" =~ ^[0-9]+$ ]] && [ "$POLICY_COUNT" -ge 5 ]; then
    echo -e "  ${GREEN}✓${NC} $POLICY_COUNT storage RLS policies"
    ((PASS++))
else
    echo -e "  ${RED}✗${NC} Only $POLICY_COUNT storage RLS policies (expected ≥5)"
    ((FAIL++))
fi

# 6. Realtime publication
echo "6. Realtime"
# SAFETY: values are hardcoded constants — never derive from external input
for table in projects progress patterns shares comments activities; do
    result=$(query "SELECT 1 FROM pg_publication_tables WHERE pubname='supabase_realtime' AND tablename='$table';")
    if echo "$result" | grep -q '"?column?"'; then
        echo -e "  ${GREEN}✓${NC} $table in supabase_realtime"
        ((PASS++))
    else
        echo -e "  ${RED}✗${NC} $table NOT in supabase_realtime"
        ((FAIL++))
    fi
done

# 7. Triggers
echo "7. Triggers"
# SAFETY: values are hardcoded constants — never derive from external input
for trigger in on_auth_user_created set_updated_at set_progress_owner_id set_patterns_updated_at; do
    result=$(query "SELECT 1 FROM information_schema.triggers WHERE trigger_name='$trigger';")
    if echo "$result" | grep -q '"?column?"'; then
        echo -e "  ${GREEN}✓${NC} trigger: $trigger"
        ((PASS++))
    else
        echo -e "  ${RED}✗${NC} trigger: $trigger MISSING"
        ((FAIL++))
    fi
done

# 8. RLS enabled
echo "8. Row Level Security"
# SAFETY: values are hardcoded constants — never derive from external input
for table in profiles projects progress patterns shares comments activities; do
    result=$(query "SELECT relrowsecurity FROM pg_class WHERE relname='$table' AND relnamespace='public'::regnamespace;")
    if echo "$result" | grep -q '"relrowsecurity": true'; then
        echo -e "  ${GREEN}✓${NC} RLS enabled on $table"
        ((PASS++))
    else
        echo -e "  ${RED}✗${NC} RLS NOT enabled on $table"
        ((FAIL++))
    fi
done

# Summary
echo ""
echo "=== Summary ==="
TOTAL=$((PASS + FAIL))
echo -e "  ${GREEN}$PASS passed${NC} / ${RED}$FAIL failed${NC} / $TOTAL total"
if [ "$FAIL" -eq 0 ]; then
    echo -e "  ${GREEN}ALL CHECKS PASSED${NC}"
    exit 0
else
    echo -e "  ${RED}SOME CHECKS FAILED${NC}"
    exit 1
fi
