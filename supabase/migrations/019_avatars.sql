-- Phase C: Avatar storage bucket (alpha1 profile completion)
-- Avatar images uploaded by users for their public-facing profile.
--
-- Public bucket — avatars are surfaced on shared content / comments / PR
-- detail screens to recipients who may not be the owner. RLS still enforces
-- per-user folder write access (only the owner can put/replace their avatar).

INSERT INTO storage.buckets (id, name, public)
VALUES ('avatars', 'avatars', true)
ON CONFLICT (id) DO NOTHING;

-- Users can upload their own avatar.
CREATE POLICY "Users can upload own avatar"
    ON storage.objects FOR INSERT
    WITH CHECK (
        bucket_id = 'avatars'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- Anyone can read avatars (bucket is public; this policy is belt-and-
-- suspenders — Supabase Storage's public-bucket flag already exposes
-- objects without auth, but explicit policies are clearer at audit time).
CREATE POLICY "Anyone can read avatars"
    ON storage.objects FOR SELECT
    USING (bucket_id = 'avatars');

-- Users can update their own avatar (replace existing file).
CREATE POLICY "Users can update own avatar"
    ON storage.objects FOR UPDATE
    USING (
        bucket_id = 'avatars'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- Users can delete their own avatar.
CREATE POLICY "Users can delete own avatar"
    ON storage.objects FOR DELETE
    USING (
        bucket_id = 'avatars'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );
