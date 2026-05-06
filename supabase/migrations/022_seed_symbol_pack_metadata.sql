-- Phase 41.1.4: seed pack metadata for the bundled JIS catalog (ADR-016 §6).
--
-- Two free-tier packs corresponding to the bundled DefaultSymbolCatalog
-- partitioned by SymbolCategory:
--   jis.knit.beginner    — KnitSymbols + CycSymbols (35 entries, KNIT category)
--   jis.crochet.beginner — CrochetSymbols (35 entries, CROCHET category)
--
-- Both at version=1 / schema_version=1. Payload paths match the files
-- uploaded to the `symbol-packs` Storage bucket via `supabase storage cp`
-- prior to this migration. payload_size + symbol_count come from the
-- `:shared:generateSymbolPackPayloads` Gradle task's manifest output;
-- regenerate by running that task after any glyph refinement that bumps
-- a pack version, then re-upload + re-INSERT with version+1.
--
-- Bundled compile-time catalog stays in-binary as offline fallback per
-- ADR-016 §4.1 — these packs are duplicative for v1 (downloaded payload
-- + bundled fallback both contain the same symbols), so a first-launch
-- user without network sees today's catalog identically.

INSERT INTO public.symbol_packs
    (id, tier, version, display_name, description, payload_path, payload_size, symbol_count)
VALUES
    ('jis.knit.beginner',    'free', 1, 'Knit – Beginner',    'Bundled JIS knitting symbols (free tier).', 'jis.knit.beginner/1/payload.json',    13558, 35),
    ('jis.crochet.beginner', 'free', 1, 'Crochet – Beginner', 'Bundled JIS crochet symbols (free tier).',  'jis.crochet.beginner/1/payload.json', 20492, 35);

INSERT INTO public.symbol_pack_locales (pack_id, locale, display_name, description)
VALUES
    ('jis.knit.beginner',    'ja', '棒針編目 – ベーシック',   'JIS L 0201-1995 棒針編目シンボル (無料)'),
    ('jis.crochet.beginner', 'ja', 'かぎ針編目 – ベーシック', 'JIS L 0201-1995 かぎ針編目シンボル (無料)');
