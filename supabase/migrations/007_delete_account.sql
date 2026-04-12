-- Phase 17: Account Deletion
-- Allows authenticated users to delete their own account.
-- CASCADE chain: auth.users -> profiles -> projects/progress/shares/comments/activities
-- handles all dependent data cleanup automatically.

CREATE OR REPLACE FUNCTION public.delete_own_account()
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = ''
AS $$
    DELETE FROM auth.users WHERE id = auth.uid();
$$;

-- Restrict execution to authenticated users only
REVOKE ALL ON FUNCTION public.delete_own_account() FROM public;
GRANT EXECUTE ON FUNCTION public.delete_own_account() TO authenticated;
