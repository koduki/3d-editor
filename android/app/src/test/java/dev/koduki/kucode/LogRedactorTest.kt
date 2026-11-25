package dev.koduki.kucode

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    @After
    fun tearDown() {
        LogRedactor.clearDynamicSecrets()
    }

    @Test
    fun redactsDynamicTokensQueryTokensCookiesHeadersAndGitHubCredentials() {
        val editorToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val adminToken = "99887766554433221100ffeeddccbbaa"
        val ghOauth = "gho_1234567890abcdefghijklmnopqrstuv"
        val ghPat = "github_pat_11ABCDEFG0123456789abcdefghijklmno"

        LogRedactor.registerSecret(editorToken)
        LogRedactor.registerSecret(adminToken)

        val raw = """
            Web UI available at http://127.0.0.1:18765/?folder=/workspace&tkn=$editorToken
            Cookie: vscode-tkn=customcookie999; other=1
            X-KuCode-Token: $adminToken
            KUCODE_ADMIN_TOKEN=$adminToken
            OAuth callback: https://github.com/login/oauth/callback?code=secret_oauth_code_123&state=ok
            Authorization: Bearer super_secret_jwt_value
            Logged in token $ghOauth and $ghPat
        """.trimIndent()

        val sanitized = LogRedactor.redact(raw)

        assertFalse(sanitized.contains(editorToken))
        assertFalse(sanitized.contains(adminToken))
        assertFalse(sanitized.contains("customcookie999"))
        assertFalse(sanitized.contains("secret_oauth_code_123"))
        assertFalse(sanitized.contains("super_secret_jwt_value"))
        assertFalse(sanitized.contains(ghOauth))
        assertFalse(sanitized.contains(ghPat))

        assertTrue(sanitized.contains("folder=/workspace"))
        assertTrue(sanitized.contains("state=ok"))
        assertTrue(sanitized.contains("[REDACTED"))
    }

    @Test
    fun redactsPemKeysUrlUserinfoFragmentsApiHeadersEnvVarsJsonAndProviderPrefixes() {
        val raw = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmU=
            -----END OPENSSH PRIVATE KEY-----
            git clone https://alice:super_secret_pw@github.com/org/repo.git
            redirect https://app.example.com/cb#access_token=frag_tok_98765&expires=3600
            X-API-Key: my_custom_x_api_key_val
            Anthropic-Api-Key: sk-ant-api03-secret_anthropic_value_1234567890
            X-Goog-Api-Key: AIzaSyDummyGoogleApiKey123456789012345
            OPENAI_API_KEY=sk-proj-1234567890abcdefghijklmnop
            {"api_key": "json_secret_9999", "normal": "hello"}
            AWS key AKIAIOSFODNN7EXAMPLE and npm npm_1234567890abcdefghij
        """.trimIndent()

        val sanitized = LogRedactor.redact(raw)

        assertFalse(sanitized.contains("b3BlbnNzaC1rZXktdjE"))
        assertFalse(sanitized.contains("super_secret_pw"))
        assertFalse(sanitized.contains("frag_tok_98765"))
        assertFalse(sanitized.contains("my_custom_x_api_key_val"))
        assertFalse(sanitized.contains("secret_anthropic_value"))
        assertFalse(sanitized.contains("AIzaSyDummyGoogleApiKey"))
        assertFalse(sanitized.contains("sk-proj-1234567890"))
        assertFalse(sanitized.contains("json_secret_9999"))
        assertFalse(sanitized.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(sanitized.contains("npm_1234567890abcdefghij"))

        assertTrue(sanitized.contains("https://alice:[REDACTED]@github.com/org/repo.git"))
        assertTrue(sanitized.contains("\"normal\": \"hello\""))
    }
}
