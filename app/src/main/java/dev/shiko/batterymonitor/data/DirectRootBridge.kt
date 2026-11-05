package dev.shiko.batterymonitor.data

import java.util.concurrent.TimeUnit

/** Direct `su -c` access for devices where root is provided by KernelSU or Magisk. */
internal object DirectRootBridge {

    data class Provider(val name: String, val version: String?) {
        fun label(): String = if (version.isNullOrBlank()) name else "$name($version)"
    }

    @Volatile
    private var cachedProvider: Provider? = null

    @Volatile
    private var checked = false

    fun provider(): Provider? = cachedProvider

    fun isAvailable(): Boolean = checked && cachedProvider != null

    /** Runs on an IO dispatcher because `su` may display an authorization prompt. */
    fun detect(): Provider? {
        val result = runSu(
            """uid=${'$'}(id -u); [ "${'$'}uid" = 0 ] || exit 1; """ +
                """if command -v ksud >/dev/null 2>&1; then """ +
                """v=${'$'}(ksud debug version 2>/dev/null | sed -n 's/^Kernel Version:[[:space:]]*//p' | head -n 1); echo "@@ROOT|KernelSU|${'$'}v"; """ +
                """elif [ -x /data/adb/ksu/bin/ksud ]; then """ +
                """v=${'$'}(/data/adb/ksu/bin/ksud debug version 2>/dev/null | sed -n 's/^Kernel Version:[[:space:]]*//p' | head -n 1); echo "@@ROOT|KernelSU|${'$'}v"; """ +
                """elif command -v magisk >/dev/null 2>&1; then """ +
                """v=${'$'}(magisk -v 2>/dev/null | head -n 1); echo "@@ROOT|Magisk|${'$'}v"; """ +
                """elif [ -d /data/adb/magisk ]; then echo "@@ROOT|Magisk|"; """ +
                """else echo "@@ROOT|root|"; fi""",
            timeoutMillis = 5_000L,
        )
        cachedProvider = result?.lineSequence()?.firstNotNullOfOrNull { line ->
            val fields = line.trim().removePrefix("@@ROOT|").split('|', limit = 2)
            if (!line.startsWith("@@ROOT|") || fields.size != 2) null
            else Provider(fields[0], fields[1].trim().takeIf(String::isNotEmpty))
        }
        checked = true
        return cachedProvider
    }

    fun exec(command: String, timeoutMillis: Long = 15_000L): String? {
        if (!isAvailable()) return null
        return runSu(command, timeoutMillis)
    }

    private fun runSu(command: String, timeoutMillis: Long): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command).start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outReader = Thread { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }
        val errReader = Thread { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
        outReader.start()
        errReader.start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return null
        }
        outReader.join(1_000L)
        errReader.join(1_000L)
        val output = stdout.toString()
        if (process.exitValue() == 0) output else output.ifBlank { stderr.toString() }
    }.getOrNull()
}
