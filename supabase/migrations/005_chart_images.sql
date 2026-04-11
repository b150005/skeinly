-- Phase 10: Chart Image Support
-- Add chart_image_urls column to patterns table for storing storage paths
ALTER TABLE public.patterns
    ADD COLUMN IF NOT EXISTS chart_image_urls JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Create Supabase Storage bucket for chart images (private)
INSERT INTO storage.buckets (id, name, public)
VALUES ('chart-images', 'chart-images', false)
ON CONFLICT (id) DO NOTHING;

-- RLS policies for chart-images bucket
-- Authenticated users can upload to their own folder
CREATE POLICY "Users can upload chart images"
    ON storage.objects FOR INSERT
    WITH CHECK (
        bucket_id = 'chart-images'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- Users can read their own images
CREATE POLICY "Users can read own chart images"
    ON storage.objects FOR SELECT
    USING (
        bucket_id = 'chart-images'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- Users can update (overwrite) their own images
CREATE POLICY "Users can update own chart images"
    ON storage.objects FOR UPDATE
    USING (
        bucket_id = 'chart-images'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- Users can delete their own images
CREATE POLICY "Users can delete own chart images"
    ON storage.objects FOR DELETE
    USING (
        bucket_id = 'chart-images'
        AND auth.uid()::text = (storage.foldername(name))[1]
    );

-- Allow reading chart images for shared/public patterns
CREATE POLICY "Users can read shared pattern chart images"
    ON storage.objects FOR SELECT
    USING (
        bucket_id = 'chart-images'
        AND EXISTS (
            SELECT 1 FROM public.patterns p
            WHERE p.visibility IN ('shared', 'public')
            AND p.chart_image_urls @> to_jsonb(name)
        )
    );
