package com.aryan.reader.shared

const val VELLUM_POLICY_BASE_URL = "https://aryan-raj3112.github.io/reader-policy"

enum class SharedLegalProfile {
    STANDARD,
    OSS
}

data class SharedLegalLinks(
    val privacyPolicyUrl: String,
    val termsUrl: String,
    val licensesUrl: String
)

fun sharedLegalLinksForProfile(profile: SharedLegalProfile): SharedLegalLinks {
    val privacyPath: String
    val termsPath: String
    when (profile) {
        SharedLegalProfile.STANDARD -> {
            privacyPath = "privacy-policy.html"
            termsPath = "terms-and-conditions.html"
        }
        SharedLegalProfile.OSS -> {
            privacyPath = "oss-privacy-policy.html"
            termsPath = "oss-terms-of-service.html"
        }
    }
    return SharedLegalLinks(
        privacyPolicyUrl = "$VELLUM_POLICY_BASE_URL/$privacyPath",
        termsUrl = "$VELLUM_POLICY_BASE_URL/$termsPath",
        licensesUrl = "$VELLUM_POLICY_BASE_URL/licenses.html"
    )
}
