package rikka.shizuku.server.util

import android.text.TextUtils
import java.util.regex.Pattern

/**
 * Utility class for input validation to prevent injection attacks and ensure system stability.
 */
object InputValidationUtils {

    private const val TAG = "InputValidationUtils"

    // Valid DNS mode values
    private val VALID_DNS_MODES = listOf(
        "off",
        "opportunistic",
        "hostname"
    )

    // Valid spoof target device identifiers
    private val VALID_SPOOF_TARGETS = listOf(
        "pixel_9_pro_xl",
        "pixel_8_pro",
        "pixel_7_pro",
        "galaxy_s24_ultra",
        "galaxy_s23_ultra",
        "galaxy_s22_ultra",
        "oneplus_12",
        "nothing_phone_2",
        "pixel_tablet",
        "pixel_fold"
    )

    /**
     * Checks if a spoof target is valid.
     *
     * @param target the target to validate
     * @return true if target is in the whitelist, false otherwise
     */
    @JvmStatic
    fun isValidSpoofTarget(target: String?): Boolean {
        if (target.isNullOrEmpty()) return false
        return VALID_SPOOF_TARGETS.contains(target.lowercase())
    }

    /**
     * Gets all valid spoof targets as a comma-separated string.
     */
    @JvmStatic
    fun getValidSpoofTargets(): String {
        return VALID_SPOOF_TARGETS.joinToString(", ")
    }

    // Pattern for validating hostname format
    // Hostname: 1-253 chars, alphanumeric and hyphens, segments separated by dots
    // Each segment: 1-63 chars, cannot start or end with hyphen
    private val HOSTNAME_PATTERN = Pattern.compile(
        "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?$"
    )

    // Pattern for simple hostname (single label)
    private val SIMPLE_HOSTNAME_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?$"
    )

    /**
     * Validates a DNS mode parameter.
     *
     * @param mode the DNS mode to validate
     * @return true if the mode is valid ("off", "opportunistic", or "hostname"), false otherwise
     */
    @JvmStatic
    fun isValidDnsMode(mode: String?): Boolean {
        if (mode.isNullOrEmpty()) {
            return false
        }
        return VALID_DNS_MODES.contains(mode.lowercase())
    }

    /**
     * Validates a DNS hostname string.
     *
     * @param hostname the hostname to validate
     * @return true if the hostname is valid, false otherwise
     */
    @JvmStatic
    fun isValidHostname(hostname: String?): Boolean {
        if (hostname.isNullOrEmpty()) {
            return false
        }

        // Check total length (max 253 characters)
        if (hostname.length > 253) {
            return false
        }

        // Check if it's a simple hostname (single label)
        if (!hostname.contains(".")) {
            return SIMPLE_HOSTNAME_PATTERN.matcher(hostname).matches()
        }

        // Check if it's a fully qualified domain name
        return HOSTNAME_PATTERN.matcher(hostname).matches()
    }

    /**
     * Sanitizes a hostname string by removing potentially dangerous characters.
     *
     * @param hostname the hostname to sanitize
     * @return sanitized hostname, or null if input is null/empty
     */
    @JvmStatic
    fun sanitizeHostname(hostname: String?): String? {
        if (hostname.isNullOrEmpty()) {
            return null
        }

        // Remove any characters that are not alphanumeric, hyphen, or dot
        return hostname.replace("[^a-zA-Z0-9.\\-]".toRegex(), "")
    }

    /**
     * Validates and sanitizes a hostname.
     *
     * @param hostname the hostname to validate and sanitize
     * @return sanitized hostname if valid, null if invalid
     */
    @JvmStatic
    fun validateAndSanitizeHostname(hostname: String?): String? {
        val sanitized = sanitizeHostname(hostname)
        if (sanitized.isNullOrEmpty() || !isValidHostname(sanitized)) {
            return null
        }
        return sanitized
    }
}
