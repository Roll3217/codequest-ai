package com.example.utils

/**
 * Centralized configuration layer for backend-dependent services.
 * Ensures the app never invents or hardcodes fake production URLs when the backend
 * has not been deployed yet.
 */
object AppConfig {

    private const val UNCONFIGURED_TOKEN = "NOT_CONFIGURED"
    const val DEFAULT_SUPABASE_URL = "https://qypwxgolicehkuljbyvp.supabase.co"

    val backendBaseUrl: String
        get() = try {
            val clazz = Class.forName("com.example.BuildConfig")
            val raw = clazz.getDeclaredField("CODEQUEST_BACKEND_URL")
                .get(null) as? String ?: ""
            if (raw == UNCONFIGURED_TOKEN) "" else raw.trim()
        } catch (_: Throwable) {
            ""
        }

    val publicProfileBaseUrl: String
        get() = try {
            val clazz = Class.forName("com.example.BuildConfig")
            val raw = clazz.getDeclaredField("CODEQUEST_PUBLIC_PROFILE_BASE_URL")
                .get(null) as? String ?: ""
            if (raw == UNCONFIGURED_TOKEN) "" else raw.trim()
        } catch (_: Throwable) {
            ""
        }

    val supabaseUrl: String
        get() = try {
            val clazz = Class.forName("com.example.BuildConfig")
            val raw = clazz.getDeclaredField("SUPABASE_URL")
                .get(null) as? String ?: ""
            normalizeSupabaseUrl(raw)
        } catch (_: Throwable) {
            DEFAULT_SUPABASE_URL
        }

    internal fun normalizeSupabaseUrl(raw: String): String {
        var cleaned = raw.trim().removeSurrounding("\"").trimEnd('/')
        if (cleaned == UNCONFIGURED_TOKEN || cleaned.isBlank()) {
            return DEFAULT_SUPABASE_URL
        }
        // Fix common "superbase" spelling typo -> "supabase"
        cleaned = cleaned
            .replace("superbase.co", "supabase.co", ignoreCase = true)
            .replace("superbase.com", "supabase.com", ignoreCase = true)
        if (!cleaned.startsWith("http://", ignoreCase = true) &&
            !cleaned.startsWith("https://", ignoreCase = true)
        ) {
            cleaned = "https://$cleaned"
        }
        // If user accidentally pasted dashboard URL like https://supabase.com/dashboard/project/qypwxgolicehkuljbyvp
        val dashboardMatch = Regex("""supabase\.com/dashboard/project/([a-zA-Z0-9]+)""").find(cleaned)
        if (dashboardMatch != null) {
            return "https://${dashboardMatch.groupValues[1]}.supabase.co"
        }
        return try {
            val host = java.net.URI(cleaned).host?.lowercase().orEmpty()
            // Reject homepage/marketing hosts (supabase.com, www.supabase.com, app.supabase.com) that lack project subdomain
            if (host.isBlank() ||
                host == "supabase.com" ||
                host == "www.supabase.com" ||
                host == "app.supabase.com"
            ) {
                DEFAULT_SUPABASE_URL
            } else {
                cleaned
            }
        } catch (_: Throwable) {
            DEFAULT_SUPABASE_URL
        }
    }

    val supabaseAnonKey: String
        get() = try {
            val clazz = Class.forName("com.example.BuildConfig")
            val raw = clazz.getDeclaredField("SUPABASE_ANON_KEY")
                .get(null) as? String ?: ""
            val cleaned = raw.trim().removeSurrounding("\"")
            if (cleaned == UNCONFIGURED_TOKEN) "" else cleaned
        } catch (_: Throwable) {
            ""
        }

    fun isSupabaseConfigured(): Boolean {
        val url = supabaseUrl
        val key = supabaseAnonKey
        return url.isNotBlank() &&
            (url.startsWith("https://") || url.startsWith("http://")) &&
            key.isNotBlank()
    }

    val supabaseHost: String
        get() {
            val url = supabaseUrl
            return if (url.isBlank()) "Not configured"
            else runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
        }

    fun isBackendConfigured(): Boolean {
        val url = backendBaseUrl
        return url.isNotBlank() &&
            (url.startsWith("https://") || url.startsWith("http://"))
    }

    fun isPublicProfileUrlConfigured(): Boolean {
        val url = publicProfileBaseUrl
        return url.isNotBlank() &&
            (url.startsWith("https://") || url.startsWith("http://"))
    }
}
