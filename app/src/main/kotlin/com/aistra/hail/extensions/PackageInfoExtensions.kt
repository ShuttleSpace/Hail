package com.aistra.hail.extensions

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat

import com.aistra.hail.HailApp.Companion.app

val ApplicationInfo.isSystemApp: Boolean
    get() = flags and ApplicationInfo.FLAG_SYSTEM == ApplicationInfo.FLAG_SYSTEM

/**
 * The name of the file when exported to sd card, in the format `AppName-VersionName-VersionNumber.apk`.
 * */
val PackageInfo.exportFileName
    get() = "${applicationInfo?.loadLabel(app.packageManager) ?: packageName}-$versionName-${
        PackageInfoCompat.getLongVersionCode(this)
    }.apk"