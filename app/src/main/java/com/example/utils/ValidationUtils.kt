package com.example.utils

import java.security.MessageDigest
import java.util.Locale

object ValidationUtils {

    private val GMAIL_REGEX = Regex("^[A-Za-z0-9._%+\\-]+@gmail\\.com$", RegexOption.IGNORE_CASE)

    fun isValidFullName(name: String): Boolean {
        return name.trim().length >= 2
    }

    /**
     * Strictly validates that the email is non-empty, well-formed, and ends with @gmail.com.
     */
    fun isValidGmailAddress(email: String): Boolean {
        val trimmed = email.trim().lowercase(Locale.ROOT)
        if (!trimmed.endsWith("@gmail.com")) return false
        val localPart = trimmed.substringBefore("@gmail.com")
        if (localPart.isBlank()) return false
        return GMAIL_REGEX.matches(trimmed)
    }

    fun isValidPassword(password: String): Boolean {
        return password.length >= 6
    }

    fun doPasswordsMatch(password: String, confirmPassword: String): Boolean {
        return password.isNotEmpty() && password == confirmPassword
    }

    fun normalizeEmail(email: String): String {
        return email.trim().lowercase(Locale.ROOT)
    }

    fun hashPassword(email: String, rawPassword: String): String {
        val normalizedEmail = normalizeEmail(email)
        val payload = "codequest_salt_v1::$normalizedEmail::$rawPassword"
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
