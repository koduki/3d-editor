package dev.koduki.kucode

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Pure-JVM secret and credential redactor applied before writing diagnostic events to disk
 * or exporting session bundles for AI analysis.
 *
 * Ensures connection tokens (`?tkn=`, `vscode-tkn`), host admin tokens (`X-KuCode-Token`),
 * OAuth query/fragment codes & tokens, Authorization / API-key headers, provider API keys
 * (GitHub, OpenAI/Anthropic `sk-`, Google `AIza`, Slack `xox*`, AWS `AKIA`/`ASIA`, HF/npm),
 * URL embedded credentials, JSON secret fields, shell secret env vars, and PEM private keys
 * never leak into persisted logs or AI prompts.
 */
internal object LogRedactor {

    private val PEM_PRIVATE_KEY_REGEX = Regex(
        "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
    )
    private val URL_USERINFO_REGEX = Regex(
        "(?i)\\b(https?|wss?|ftp|ssh|git)://([^:@\\s\"'/]+):([^@\\s\"'/]+)@",
    )
    private val QUERY_SECRET_REGEX = Regex(
        "(?i)([?&#])(tkn|token|access_token|refresh_token|id_token|client_secret|code|api_key|apikey|key|secret|password|passwd|x-amz-signature|x-amz-security-token)=([^&#\\s\"']+)",
    )
    private val COOKIE_SECRET_REGEX = Regex(
        "(?i)(vscode-tkn)=([^;\\s\"']+)",
    )
    private val SENSITIVE_HEADER_REGEX = Regex(
        "(?im)((?:X-KuCode-Token|X-API-Key|X-Goog-Api-Key|Anthropic-Api-Key|Proxy-Authorization)\\s*:\\s*)([^\\r\\n]+)",
    )
    private val AUTH_HEADER_REGEX = Regex(
        "(?im)(Authorization\\s*:\\s*(?:(?:Bearer|Basic|Token|Digest|AWS4-HMAC-SHA256)\\s+)?)([^\\r\\n\"']+)",
    )
    private val ENV_SECRET_REGEX = Regex(
        "(?i)\\b([A-Z0-9_]*(?:TOKEN|API_KEY|SECRET|PASSWORD|PASSWD)=)([^\\s\"'`]+)",
    )
    private val JSON_SECRET_REGEX = Regex(
        "(?i)(\"(?:access_token|refresh_token|id_token|client_secret|api_key|apiKey|password|secret|token|authorization)\"\\s*:\\s*\")([^\"]+)(\")",
    )
    private val GITHUB_TOKEN_REGEX = Regex(
        "\\b(gh[pousr]_[A-Za-z0-9_]{16,}|github_pat_[A-Za-z0-9_]{16,})\\b",
    )
    private val OPENAI_ANTHROPIC_KEY_REGEX = Regex(
        "\\b(sk-(?:proj-|ant-[A-Za-z0-9_-]*|svcacct-)?[A-Za-z0-9_-]{20,})\\b",
    )
    private val GOOGLE_API_KEY_REGEX = Regex(
        "\\b(AIza[0-9A-Za-z_-]{35})\\b",
    )
    private val SLACK_TOKEN_REGEX = Regex(
        "\\b(xox[baprs]-[0-9A-Za-z-]{10,})\\b",
    )
    private val AWS_KEY_REGEX = Regex(
        "\\b((?:AKIA|ASIA)[0-9A-Z]{16})\\b",
    )
    private val PACKAGE_TOKEN_REGEX = Regex(
        "\\b(hf_[A-Za-z0-9]{20,}|npm_[A-Za-z0-9]{20,}|pypi-[A-Za-z0-9_-]{20,})\\b",
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
        out = PEM_PRIVATE_KEY_REGEX.replace(out, "[REDACTED_PRIVATE_KEY]")
        out = URL_USERINFO_REGEX.replace(out, "$1://$2:[REDACTED]@")
        out = QUERY_SECRET_REGEX.replace(out, "$1$2=[REDACTED]")
        out = COOKIE_SECRET_REGEX.replace(out, "$1=[REDACTED]")
        out = SENSITIVE_HEADER_REGEX.replace(out, "$1[REDACTED]")
        out = AUTH_HEADER_REGEX.replace(out, "$1[REDACTED]")
        out = ENV_SECRET_REGEX.replace(out, "$1[REDACTED]")
        out = JSON_SECRET_REGEX.replace(out, "$1[REDACTED]$3")
        out = GITHUB_TOKEN_REGEX.replace(out, "[REDACTED_GH_TOKEN]")
        out = OPENAI_ANTHROPIC_KEY_REGEX.replace(out, "[REDACTED_API_KEY]")
        out = GOOGLE_API_KEY_REGEX.replace(out, "[REDACTED_GOOGLE_KEY]")
        out = SLACK_TOKEN_REGEX.replace(out, "[REDACTED_SLACK_TOKEN]")
        out = AWS_KEY_REGEX.replace(out, "[REDACTED_AWS_KEY]")
        out = PACKAGE_TOKEN_REGEX.replace(out, "[REDACTED_API_KEY]")
        return out
    }
}
