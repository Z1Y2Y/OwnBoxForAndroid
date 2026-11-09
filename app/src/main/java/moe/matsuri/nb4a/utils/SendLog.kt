package moe.matsuri.nb4a.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.CrashHandler
import java.io.File
import java.io.FileInputStream
import java.io.IOException

object SendLog {
    // Create a sanitized full log and send.
    fun sendLog(context: Context, title: String) {
        val safeTitle = if (title.isBlank() || title.contains("NB4A", ignoreCase = true)) {
            title.replace("NB4A", "OWN", ignoreCase = true).ifBlank { "OWN" }
        } else {
            title
        }
        val logFile = File.createTempFile(
            "$safeTitle ",
            ".log",
            File(app.cacheDir, "log").also { it.mkdirs() },
        )

        val secret = readStoredClashApiSecret()
        val report = if (secret == null) {
            "Sensitive settings could not be read safely; diagnostic logs were omitted.\n"
        } else {
            val rawContent = buildString {
                append(CrashHandler.buildReportHeader())
                append("Logcat: \n\n")
                append(readLogcat())
                append("\n\n")
                append(getNekoLog(0).toString(Charsets.UTF_8))
            }
            LogExportSanitizer.sanitize(rawContent, secret)
        }
        logFile.writeText(report)

        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/x-log")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            context, BuildConfig.APPLICATION_ID + ".cache", logFile
                        )
                    ), context.getString(R.string.abc_shareactionprovider_share_with)
            )
        )
    }

    private fun readStoredClashApiSecret(): String? = try {
        // Read without DataStore.clashApiSecret, whose getter creates a new secret when absent.
        PublicDatabase.kvPairDao["clashApiSecret"]?.string.orEmpty()
    } catch (_: Exception) {
        null
    }

    private fun readLogcat(): String = try {
        Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    } catch (e: IOException) {
        "Export logcat error: ${CrashHandler.formatThrowable(e)}"
    }

    // Get log bytes from neko.log
    fun getNekoLog(max: Long): ByteArray {
        return try {
            val file = File(
                SagerNet.application.cacheDir,
                "neko.log"
            )
            val len = file.length()
            val stream = FileInputStream(file)
            if (max in 1 until len) {
                stream.skip(len - max) // TODO string?
            }
            stream.use { it.readBytes() }
        } catch (e: Exception) {
            e.stackTraceToString().toByteArray()
        }
    }
}
