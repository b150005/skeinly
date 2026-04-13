-- Phase 22.1 + 23: Progress Photos + ActivityType.CREATED

-- 1. Create Supabase Storage bucket for progress photos (private)
INSERT INTO storage.buckets (id, name, public)
VALUES ('progress-photos', 'progress-photos', false)
ON CONFLICT (id) DO NOTHING;

-- 2. RLS policies for progress-photos bucket

-- Users can upload progress photos to their own folder
CREATE POLICY "Users can upload progress photos"
    ON storage.objects FOR INSERT
    WITH CHECK (
        bucket_id = 'progress-photos'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- Users can read their own progress photos
CREATE POLICY "Users can read own progress photos"
    ON storage.objects FOR SELECT
    USING (
        bucket_id = 'progress-photos'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- Users can update their own progress photos
CREATE POLICY "Users can update own progress photos"
    ON storage.objects FOR UPDATE
    USING (
        bucket_id = 'progress-photos'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- Users can delete their own progress photos
CREATE POLICY "Users can delete own progress photos"
    ON storage.objects FOR DELETE
    USING (
        bucket_id = 'progress-photos'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- Users can read progress photos for shared projects
CREATE POLICY "Users can read shared project progress photos"
    ON storage.objects FOR SELECT
    USING (
        bucket_id = 'progress-photos'
        AND EXISTS (
            SELECT 1 FROM public.progress pr
            JOIN public.shares s ON s.pattern_id = (
                SELECT p.pattern_id FROM public.projects p WHERE p.id = pr.project_id
            )
            WHERE pr.owner_id::text = (storage.foldername(name))[1]
              AND s.to_user_id = auth.uid()
        )
    );

-- 3. Update activities.type CHECK constraint to include 'created'
-- Drop the existing constraint and recreate with the new value
ALTER TABLE public.activities
    DROP CONSTRAINT IF EXISTS activities_type_check;
ALTER TABLE public.activities
    ADD CONSTRAINT activities_type_check
    CHECK (type IN ('shared', 'commented', 'forked', 'completed', 'started', 'created'));
