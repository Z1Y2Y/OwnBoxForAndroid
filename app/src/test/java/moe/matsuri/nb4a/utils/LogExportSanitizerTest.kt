package moe.matsuri.nb4a.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder

class LogExportSanitizerTest {
    @Test
    fun sanitizesSettingConfigAuthorizationAndUrlRepresentations() {
        val fakeSecret = "fake-secret+value/with spaces"
        val encodedSecret = URLEncoder.encode(fakeSecret, "UTF-8")
        val input = """
            Settings: clashApiSecret: $fakeSecret
            Config: {"experimental":{"clash_api":{"secret":"$fakeSecret"}}}
            Request: Authorization: Bearer $fakeSecret
            Dashboard: http://127.0.0.1:9090/ui?secret=$fakeSecret
            Encoded: token=$encodedSecret
        """.trimIndent()

        val exported = LogExportSanitizer.sanitize(input, fakeSecret)

        assertFalse(exported.contains(fakeSecret))
        assertFalse(exported.contains(encodedSecret))
        assertTrue(exported.contains("[REDACTED]"))
    }

    @Test
    fun redactsSecretFieldsEvenWhenTheValueIsNotKnown() {
        val exported = LogExportSanitizer.sanitize(
            "Config: {\"clash_api\":{\"secret\":\"unknown-value\"}} token=unknown-token",
            secret = "",
        )

        assertFalse(exported.contains("unknown-value"))
        assertFalse(exported.contains("unknown-token"))
        assertTrue(exported.contains("[REDACTED]"))
    }

    @Test
    fun crashSettingsAndSendLogUseDefenseInDepthBeforeExport() {
        assertTrue(LogExportSanitizer.isSensitiveSettingKey("clashApiSecret"))
        assertTrue(LogExportSanitizer.isSensitiveSettingKey("clash_api_secret"))
        assertFalse(LogExportSanitizer.isSensitiveSettingKey("clashApiEnabled"))

        val crashHandler = sourceFile("src/main/java/io/nekohasekai/sagernet/utils/CrashHandler.kt").readText()
        val sendLog = sourceFile("src/main/java/moe/matsuri/nb4a/utils/SendLog.kt").readText()
        assertTrue(crashHandler.contains("LogExportSanitizer.isSensitiveSettingKey(pair.key)"))
        assertTrue(sendLog.contains("LogExportSanitizer.sanitize(rawContent, secret)"))
        assertTrue(sendLog.indexOf("LogExportSanitizer.sanitize(rawContent, secret)") < sendLog.indexOf("logFile.writeText(report)"))
        assertTrue(sendLog.contains("diagnostic logs were omitted"))
    }

    private fun sourceFile(relative: String): File =
        sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
}
