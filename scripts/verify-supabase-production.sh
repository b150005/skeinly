#!/usr/bin/env bash
# Verify Supabase production deployment for Knit Note
# Usage: ./scripts/verify-supabase-production.sh
# Requires: supabase CLI authenticated and linked to knit-note project

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'
PASS=0
FAIL=0

query() {
    supabase db query --linked "$1" -o json 2>/dev/null | grep -v '"boundary"' | grep -v '"warning"'
}

echo "=== Knit Note — Supabase Production Verification ==="
echo ""

# 1. Migration state — verify via DB query instead of parsing CLI table output
echo "1. Migrations"
EXPECTED_MIGRATIONS="001 002 003 004 005 006 007 008"
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
if [ "$APPLIED" -eq 8 ]; then
    echo -e "  ${GREEN}✓${NC} All 8 migrations applied"
    ((PASS++))
else
    echo -e "  ${RED}✗${NC} $APPLIED of 8 applied, missing:$MISSING"
    ((FAIL++))
fi

# 2. Tables
echo "2. Tables"
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
POLICY_COUNT=$(echo "$STORAGE_POLICIES" | grep -o '"cnt": [0-9]*' | grep -o '[0-9]*' || echo "0")
if [ "$POLICY_COUNT" -ge 5 ]; then
    echo -e "  ${GREEN}✓${NC} $POLICY_COUNT storage RLS policies"
    ((PASS++))
else
    echo -e "  ${RED}✗${NC} Only $POLICY_COUNT storage RLS policies (expected ≥5)"
    ((FAIL++))
fi

# 6. Realtime publication
echo "6. Realtime"
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
