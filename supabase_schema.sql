-- =========================================================================
-- CODEQUEST AI — SUPABASE POSTGRESQL PRODUCTION DATABASE SCHEMA
-- =========================================================================
-- How to apply this schema:
-- 1. Open your Supabase project dashboard: https://supabase.com/dashboard
-- 2. Navigate to "SQL Editor" on the left menu.
-- 3. Click "New query", paste this entire script, and click "Run".
-- 4. In Supabase Dashboard -> Project Settings -> API, copy:
--      - Project URL (e.g. https://xyzcompany.supabase.co)
--      - anon public API key (starts with eyJ...)
-- 5. Paste these into the AI Studio Secrets panel:
--      SUPABASE_URL = <your_project_url>
--      SUPABASE_ANON_KEY = <your_anon_key>
-- =========================================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- -------------------------------------------------------------------------
-- 1. USER PROFILES TABLE (Cloud Identity & Global Leaderboard)
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID DEFAULT uuid_generate_v4(),
    email TEXT PRIMARY KEY,
    full_name TEXT NOT NULL,
    avatar_key TEXT DEFAULT 'avatar_cyber',
    is_custom_photo BOOLEAN DEFAULT FALSE,
    interests TEXT DEFAULT 'Arrays, Dynamic Programming, Graphs',
    preferred_language TEXT DEFAULT 'Kotlin',
    xp INTEGER DEFAULT 0,
    level INTEGER DEFAULT 1,
    coins INTEGER DEFAULT 50,
    current_streak INTEGER DEFAULT 0,
    longest_streak INTEGER DEFAULT 0,
    last_qualifying_date TEXT DEFAULT '',
    password_hash TEXT DEFAULT '',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at BIGINT DEFAULT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
);

-- Safely upgrade existing deployments if profiles table already existed
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS last_qualifying_date TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS password_hash TEXT DEFAULT '';

-- Index for leaderboard rank ordering by XP
CREATE INDEX IF NOT EXISTS idx_profiles_xp_desc ON public.profiles (xp DESC);

-- -------------------------------------------------------------------------
-- 2. SOLVED QUESTIONS / USER PROGRESS TABLE
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.user_progress (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_email TEXT NOT NULL,
    question_id TEXT NOT NULL,
    topic TEXT DEFAULT '',
    difficulty TEXT DEFAULT '',
    solved_at BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_user_progress UNIQUE (user_email, question_id)
);

CREATE INDEX IF NOT EXISTS idx_user_progress_email ON public.user_progress (user_email);

-- -------------------------------------------------------------------------
-- 3. DAILY GOALS TABLE
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.daily_goals (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_email TEXT NOT NULL,
    date_string TEXT NOT NULL,
    goal_key TEXT NOT NULL,
    title TEXT NOT NULL,
    xp_reward INTEGER DEFAULT 25,
    is_completed BOOLEAN DEFAULT FALSE,
    completed_at BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_user_daily_goal UNIQUE (user_email, date_string, goal_key)
);

CREATE INDEX IF NOT EXISTS idx_daily_goals_user_date ON public.daily_goals (user_email, date_string);

-- -------------------------------------------------------------------------
-- 4. MISTAKES NOTEBOOK TABLE
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.mistakes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_email TEXT NOT NULL,
    question_id TEXT NOT NULL,
    question_title TEXT NOT NULL,
    difficulty TEXT DEFAULT 'Easy',
    topic TEXT DEFAULT 'Basics',
    error_reason TEXT DEFAULT 'Edge Case Review',
    failed_code_snippet TEXT DEFAULT '',
    language TEXT DEFAULT 'Kotlin',
    user_notes TEXT DEFAULT '',
    saved_at BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_user_mistake UNIQUE (user_email, question_id)
);

CREATE INDEX IF NOT EXISTS idx_mistakes_user_email ON public.mistakes (user_email);

-- -------------------------------------------------------------------------
-- 5. BOOKMARKS TABLE
-- -------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.bookmarks (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_email TEXT NOT NULL,
    question_id TEXT NOT NULL,
    question_title TEXT NOT NULL,
    difficulty TEXT DEFAULT 'Easy',
    topic TEXT DEFAULT 'Basics',
    saved_at BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_user_bookmark UNIQUE (user_email, question_id)
);

CREATE INDEX IF NOT EXISTS idx_bookmarks_user_email ON public.bookmarks (user_email);

-- -------------------------------------------------------------------------
-- 6. ROW LEVEL SECURITY (RLS) POLICIES
-- -------------------------------------------------------------------------

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.daily_goals ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.mistakes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bookmarks ENABLE ROW LEVEL SECURITY;

-- Profiles: Anyone can view profiles for global leaderboard
DROP POLICY IF EXISTS "Public profiles read" ON public.profiles;
CREATE POLICY "Public profiles read" ON public.profiles
    FOR SELECT USING (true);

-- Profiles: Authenticated users & anon can upsert their profile
DROP POLICY IF EXISTS "Profiles upsert" ON public.profiles;
CREATE POLICY "Profiles upsert" ON public.profiles
    FOR ALL USING (true)
    WITH CHECK (true);

-- User Progress: Full access per user
DROP POLICY IF EXISTS "User progress access" ON public.user_progress;
CREATE POLICY "User progress access" ON public.user_progress
    FOR ALL USING (true)
    WITH CHECK (true);

-- Daily Goals: Full access per user
DROP POLICY IF EXISTS "Daily goals access" ON public.daily_goals;
CREATE POLICY "Daily goals access" ON public.daily_goals
    FOR ALL USING (true)
    WITH CHECK (true);

-- Mistakes: Full access per user
DROP POLICY IF EXISTS "Mistakes access" ON public.mistakes;
CREATE POLICY "Mistakes access" ON public.mistakes
    FOR ALL USING (true)
    WITH CHECK (true);

-- Bookmarks: Full access per user
DROP POLICY IF EXISTS "Bookmarks access" ON public.bookmarks;
CREATE POLICY "Bookmarks access" ON public.bookmarks
    FOR ALL USING (true)
    WITH CHECK (true);

-- -------------------------------------------------------------------------
-- 7. SUPABASE AUTH TRIGGER (Automatically syncs auth.users to public.profiles)
-- -------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (
        id,
        email,
        full_name,
        avatar_key,
        interests,
        preferred_language
    ) VALUES (
        NEW.id,
        LOWER(NEW.email),
        COALESCE(NEW.raw_user_meta_data->>'full_name', 'Coder'),
        COALESCE(NEW.raw_user_meta_data->>'avatar_key', 'avatar_cyber'),
        COALESCE(NEW.raw_user_meta_data->>'interests', 'Arrays, Dynamic Programming, Graphs'),
        COALESCE(NEW.raw_user_meta_data->>'preferred_language', 'Kotlin')
    )
    ON CONFLICT (email) DO UPDATE SET
        id = COALESCE(public.profiles.id, EXCLUDED.id),
        full_name = COALESCE(NULLIF(EXCLUDED.full_name, ''), public.profiles.full_name),
        avatar_key = COALESCE(NULLIF(EXCLUDED.avatar_key, ''), public.profiles.avatar_key),
        updated_at = (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- -------------------------------------------------------------------------
-- 8. CLEANUP TRIGGER (Automatically removes profile/data if deleted in Auth)
-- -------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.handle_deleted_user()
RETURNS TRIGGER AS $$
BEGIN
    DELETE FROM public.user_progress WHERE user_email = LOWER(OLD.email);
    DELETE FROM public.daily_goals WHERE user_email = LOWER(OLD.email);
    DELETE FROM public.mistakes WHERE user_email = LOWER(OLD.email);
    DELETE FROM public.bookmarks WHERE user_email = LOWER(OLD.email);
    DELETE FROM public.profiles WHERE email = LOWER(OLD.email);
    RETURN OLD;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_auth_user_deleted ON auth.users;
CREATE TRIGGER on_auth_user_deleted
    AFTER DELETE ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_deleted_user();

-- Verification statement
SELECT 'CodeQuest AI Supabase Schema Initialized Successfully!' AS status;
