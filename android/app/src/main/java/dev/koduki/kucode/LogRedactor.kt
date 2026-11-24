package dev.koduki.kucode

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Pure-JVM secret and credential redactor applied before writing diagnostic events to disk
 * or exporting session bundles for AI analysis.
 *
 * Ensures connection tokens (`?tkn=`, `vscode-tkn`), host admin tokens (`X-KuCode-Token`),
 * OAuth query codes/tokens, Authorization headers, and GitHub PAT/OAuth tokens (`ghp_`, `gho_`,
 * `github_pat_`) never leak into persisted logs or AI prompts.
 */
internal object LogRedactor {

    private val QUERY_SECRET_REGEX = Regex(
        "(?i)([?&])(tkn|token|access_token|refresh_token|id_token|client_secret|code)=([^&#\\s\"']+)",
    )
    private val COOKIE_SECRET_REGEX = Regex(
        "(?i)(vscode-tkn)=([^;\\s\"']+)",
    )
    private val HEADER_TOKEN_REGEX = Regex(
        "(?i)(X-KuCode-Token\\s*:\\s*)([^\\r\\n\\s\"']+)",
    )
    private val ENV_TOKEN_REGEX = Regex(
        "(KUCODE_ADMIN_TOKEN=)([^\\s\"']+)",
    )
    private val AUTH_HEADER_REGEX = Regex(
        "(?i)(Authorization\\s*:\\s*(?:Bearer|Basic|token)\\s+)([^\\r\\n\\s\"']+)",
    )
    private val GITHUB_TOKEN_REGEX = Regex(
        "\\b(gh[pousr]_[A-Za-z0-9_]{16,}|github_pat_[A-Za-z0-9_]{16,})\\b",
    )

    private val dynamicSecrets = CopyOnWriteArraySet<String>()

    fun registerSecret(secret: String?) {
        val trimmed = secret?.trim().orEmpty()
        if (trimmed.length >= 8) {
            dynamicSecrets.add(trimmed)
        }
    }

    fun clearDynamicSecrets() {
        dynamicSecrets.clear()
    }

    fun redact(raw: String, extraSecrets: Collection<String> = emptyList()): String {
        if (raw.isEmpty()) return raw
        var out = raw
        for (secret in dynamicSecrets) {
            if (secret.length >= 8 && out.contains(secret)) {
                out = out.replace(secret, "[REDACTED_TOKEN]")
            }
        }
        for (secret in extraSecrets) {
            val s = secret.trim()
            if (s.length >= 8 && out.contains(s)) {
                out = out.replace(s, "[REDACTED_TOKEN]")
            }
        }
        out = QUERY_SECRET_REGEX.replace(out, "$1$2=[REDACTED]")
        out = COOKIE_SECRET_REGEX.replace(out, "$1=[REDACTED]")
        out = HEADER_TOKEN_REGEX.replace(out, "$1[REDACTED]")
        out = ENV_TOKEN_REGEX.replace(out, "$1[REDACTED]")
        out = AUTH_HEADER_REGEX.replace(out, "$1[REDACTED]")
        out = GITHUB_TOKEN_REGEX.replace(out, "[REDACTED_GH_TOKEN]")
        return out
    }
}
