-- Knit Note: Direct Sharing Support
-- Phase 4b-2b: Direct user-to-user sharing
-- Adds RLS policies for profile search and share status updates

-- Drop the restrictive "own profile only" SELECT policy
-- Replace with broader policy allowing authenticated users to search profiles
DROP POLICY IF EXISTS "Users can read own profile" ON public.profiles;

CREATE POLICY "Authenticated users can search profiles"
    ON public.profiles FOR SELECT
    USING (auth.role() = 'authenticated');

-- Recipients can accept/decline shares sent to them
CREATE POLICY "Recipients can update received share status"
    ON public.shares FOR UPDATE
    USING (to_user_id = auth.uid())
    WITH CHECK (to_user_id = auth.uid());
