package com.nuvio.app.features.cloudstream

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream

internal actual object CloudStreamPlatformStorage {
    private const val preferencesName = "nuvio_cloudstream"
    private const val stateKey = "state"
    private const val packagesDirectoryName = "cloudstream/packages"

    private var appContext: Context? = null
    private var preferences: SharedPreferences? = null
    private var activeProfileId: Int = 1

    private fun getContext(): Context {
        appContext?.let { return it }
        val fallback = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val method = activityThreadClass.getMethod("currentApplication")
            method.invoke(null) as? Context
        }.getOrNull()
        if (fallback != null) {
            initialize(fallback)
            return fallback
        }
        error("CloudStream storage is not initialized")
    }

    private fun getPreferences(): SharedPreferences? {
        if (preferences == null) {
            runCatching { getContext() }
        }
        return preferences
    }

    actual fun initialize(context: Any?) {
        val androidContext = context as? Context ?: return
        appContext = androidContext.applicationContext
        preferences = androidContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        CloudStreamPlatformRuntime.initialize(androidContext)
    }

    actual fun setActiveProfile(profileId: Int) {
        activeProfileId = profileId.coerceAtLeast(1)
    }

    actual fun loadState(profileId: Int): String? =
        getPreferences()?.getString("${stateKey}_$profileId", null)

    actual fun saveState(profileId: Int, payload: String) {
        getPreferences()?.edit()?.putString("${stateKey}_$profileId", payload)?.apply()
    }

    actual fun savePackageAtomically(storageKey: String, bytes: ByteArray) {
        val directory = packagesDirectory()
        val destination = File(directory, "$storageKey.cs3")
        val temporary = File.createTempFile(storageKey, ".tmp", directory)
        val backup = File(directory, "$storageKey.backup")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (backup.exists()) {
                backup.setWritable(true)
                backup.delete()
            }
            if (destination.exists()) {
                destination.setWritable(true)
                if (!destination.renameTo(backup)) {
                    error("Could not prepare existing CloudStream package for update")
                }
            }
            if (!temporary.renameTo(destination)) {
                if (backup.exists()) {
                    backup.setWritable(true)
                    backup.renameTo(destination)
                }
                error("Could not commit CloudStream package")
            }
            if (backup.exists()) {
                backup.setWritable(true)
                backup.delete()
            }
        } finally {
            temporary.setWritable(true)
            temporary.delete()
        }
    }

    actual fun packageExists(storageKey: String): Boolean {
        val scoped = File(packagesDirectory(), "$storageKey.cs3")
        if (scoped.isFile) return true
        val legacy = legacyPackage(storageKey)
        return legacy.isFile && legacy.copyTo(scoped, overwrite = false).isFile
    }

    actual fun migratePackage(oldStorageKey: String, newStorageKey: String): Boolean {
        val directory = packagesDirectory()
        val source = File(directory, "$oldStorageKey.cs3")
        val destination = File(directory, "$newStorageKey.cs3")
        if (destination.isFile) return true
        source.setWritable(true)
        if (source.isFile && source.renameTo(destination)) return true
        val legacy = legacyPackage(oldStorageKey)
        return legacy.isFile && legacy.copyTo(destination, overwrite = false).isFile
    }

    actual fun packagePath(storageKey: String): String? {
        if (!packageExists(storageKey)) return null
        return File(packagesDirectory(), "$storageKey.cs3").absolutePath
    }

    actual fun deletePackage(storageKey: String) {
        val packageFile = File(packagesDirectory(), "$storageKey.cs3")
        val backupFile = File(packagesDirectory(), "$storageKey.backup")
        if (packageFile.exists()) {
            packageFile.setWritable(true)
            packageFile.delete()
        }
        if (backupFile.exists()) {
            backupFile.setWritable(true)
            backupFile.delete()
        }
    }

    actual fun clearPackages() {
        packagesRootDirectory().deleteRecursively()
    }

    actual fun clearAllState() {
        getPreferences()?.edit()?.clear()?.apply()
    }

    private fun packagesDirectory(): File {
        return File(packagesRootDirectory(), "profile-$activeProfileId").apply { mkdirs() }
    }

    private fun legacyPackage(storageKey: String): File {
        return File(packagesRootDirectory(), "$storageKey.cs3")
    }

    private fun packagesRootDirectory(): File {
        val context = getContext()
        return File(context.filesDir, packagesDirectoryName).apply { mkdirs() }
    }
}
