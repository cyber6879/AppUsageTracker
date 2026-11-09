package com.monitor.usagetracker.data.collector

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import com.monitor.usagetracker.data.local.entity.AppMetadataEntity

class PackageMetadataResolver(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    private val iconCache = object : LruCache<String, Drawable>(120) {}

    private val hardcodedCoreExcludedPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings.intelligence",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        context.packageName
    )

    private val launcherPackagesCache: Set<String> by lazy {
        queryLauncherPackages()
    }

    private fun queryLauncherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolveInfos.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    fun isCoreFilteredPackage(packageName: String): Boolean {
        return packageName in hardcodedCoreExcludedPackages || packageName in launcherPackagesCache
    }

    fun resolveAppMetadata(packageName: String): AppMetadataEntity {
        val now = System.currentTimeMillis()
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            val label = packageManager.getApplicationLabel(appInfo).toString().ifBlank { packageName }
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            AppMetadataEntity(
                packageName = packageName,
                appName = label,
                isSystemApp = isSystem,
                isCoreFiltered = isCoreFilteredPackage(packageName),
                lastUpdatedMs = now
            )
        } catch (e: PackageManager.NameNotFoundException) {
            AppMetadataEntity(
                packageName = packageName,
                appName = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                isSystemApp = false,
                isCoreFiltered = isCoreFilteredPackage(packageName),
                lastUpdatedMs = now
            )
        }
    }

    fun getAppIcon(packageName: String): Drawable? {
        iconCache.get(packageName)?.let { return it }
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            iconCache.put(packageName, drawable)
            drawable
        } catch (e: Exception) {
            null
        }
    }
}
