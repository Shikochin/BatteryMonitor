package dev.shiko.batterymonitor.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * `PackageManager` helpers for the "使用场景" list: labels, launcher icons, uid → package
 * resolution and the "is this package worth showing" rules.
 *
 * The app is Shizuku-only, so this is the *only* place that talks to `PackageManager`; all the
 * statistics come from `dumpsys batterystats`. Label/icon loading is cached because icon decoding
 * is not free and the list is rebuilt every few seconds.
 */
internal class PackageVisuals(context: Context) {

    /** Label + launcher icon, loaded once per package. */
    data class AppVisual(
        val label: String,
        val icon: ImageBitmap?,
    )

    private val context: Context = context.applicationContext
    private val packageManager: PackageManager = this.context.packageManager

    /** Concurrent because scenario refreshes can overlap while a previous icon is still loading. */
    private val cache = ConcurrentHashMap<String, AppVisual>()

    /** Lazily resolved launcher package; `null` means "not resolved yet or none". */
    @Volatile
    private var cachedHomePackage: String? = null

    /** Loads (and caches) label + icon for every requested package. */
    fun visualsFor(packageNames: Collection<String>): Map<String, AppVisual> {
        if (packageNames.isEmpty()) return emptyMap()
        val visuals = HashMap<String, AppVisual>(packageNames.size)
        for (packageName in packageNames) {
            visuals[packageName] = cache[packageName] ?: loadVisual(packageName).also {
                cache[packageName] = it
            }
        }
        return visuals
    }

    /**
     * Packages sharing a Linux uid. `getPackagesForUid` can return `null` for a uid whose package
     * was uninstalled but which still appears in the batterystats dump.
     */
    fun packagesForUid(uid: Int): List<String> =
        runCatching { packageManager.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty()

    /** Anything installed except our own package. */
    fun isReportablePackage(packageName: String): Boolean =
        packageName.isNotEmpty() && packageName != context.packageName

    /**
     * Launchable apps plus the current home/launcher app. Launchers do not answer
     * [PackageManager.getLaunchIntentForPackage], but the reference list contains 系统桌面.
     */
    fun isLaunchableOrLauncher(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        if (packageName == homePackageName()) return true
        if (isLauncherPackageName(packageName)) return true
        return runCatching { packageManager.getLaunchIntentForPackage(packageName) != null }
            .getOrDefault(false)
    }

    /** Package currently handling `ACTION_MAIN` + `CATEGORY_HOME` (the launcher). */
    private fun homePackageName(): String? {
        cachedHomePackage?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val resolved = runCatching {
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        val packageName = resolved?.activityInfo?.packageName
            ?.takeUnless { it == "android" || it == "com.android.internal.app.ResolverActivity" }
        cachedHomePackage = packageName
        return packageName
    }

    /**
     * OEM launchers sometimes ship several packages and the resolved home app can move between
     * them (`com.android.launcher` here, `com.miui.home`, `com.oppo.launcher` elsewhere).
     */
    private fun isLauncherPackageName(packageName: String): Boolean {
        val lower = packageName.lowercase()
        if (lower.contains("launcher")) return true
        return LAUNCHER_PACKAGE_HINTS.any { lower == it }
    }

    private fun loadVisual(packageName: String): AppVisual {
        val applicationInfo = try {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return AppVisual(label = packageName, icon = null)
        }

        val label = try {
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

        val icon = try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_MAX_PIXELS
            val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_MAX_PIXELS
            drawable.toBitmap(
                width = intrinsicWidth.coerceAtMost(ICON_MAX_PIXELS),
                height = intrinsicHeight.coerceAtMost(ICON_MAX_PIXELS),
            ).asImageBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }

        return AppVisual(label = label, icon = icon)
    }

    private companion object {
        /** Icons are downscaled to this many pixels; list rows never need more. */
        const val ICON_MAX_PIXELS = 192

        /** Well-known launcher packages that do not contain the word "launcher". */
        val LAUNCHER_PACKAGE_HINTS = listOf(
            "com.android.home",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.coloros.launcher",
            "com.oplus.launcher",
            "com.huawei.android.launcher",
            "com.bbk.launcher2",
            "com.sec.android.app.launcher",
            "com.transsion.hilauncher",
        )
    }
}
