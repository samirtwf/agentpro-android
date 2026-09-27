/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import com.google.android.material.color.DynamicColors
import org.linphone.compatibility.Compatibility
import org.linphone.core.CoreContext
import org.linphone.core.CorePreferences
import org.linphone.core.Factory
import org.linphone.core.LogCollectionState
import org.linphone.core.LogLevel
import org.linphone.core.ManagedConfigurationReceiver
import org.linphone.core.VFS
import org.linphone.core.tools.Log

@MainThread
class LinphoneApplication : Application(), SingletonImageLoader.Factory {
    companion object {
        private const val TAG = "[ProAgent Application]"

        @SuppressLint("StaticFieldLeak")
        lateinit var corePreferences: CorePreferences

        @SuppressLint("StaticFieldLeak")
        lateinit var coreContext: CoreContext
    }

    override fun onCreate() {
        super.onCreate()
        val context = applicationContext

        // AgentPro: the interface follows the device's language, like Rattil, unless one is
        // chosen in Settings → Language; AppCompat keeps that choice across launches.

        val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ProAgent:AppCreation"
        )
        wakeLock.acquire(20000L) // 20 seconds

        Factory.instance().setLogCollectionPath(context.filesDir.absolutePath)
        Factory.instance().enableLogCollection(LogCollectionState.Enabled)
        // For VFS
        Factory.instance().setCacheDir(context.cacheDir.absolutePath)

        corePreferences = CorePreferences(context)
        corePreferences.copyAssetsFromPackage()

        // AgentPro: storage encryption on by default. Without it, .linphonerc sits in the app's
        // files directory with the SIP account's password in clear - anyone with the handset,
        // a backup of it, or root on it reads the credentials to the customer's PBX straight
        // out of a text file. The same switch was made on the desktop build.
        //
        // Everything needed was already here and only ever reached through a Settings toggle
        // that most installs would never touch: VFS.setup() arms the SDK's encrypted VFS
        // (AES-256-GCM) over linphonerc, the call history, the chat database and the ZRTP
        // secrets, keyed by 32 random bytes generated per device and held in
        // EncryptedSharedPreferences under the Android Keystore - not compiled into the APK,
        // so extracting one device's key does not open another's.
        //
        // Enabled here, before createConfigWithFactory below, because the encryption has to be
        // armed before the Core opens its first file. Existing installs migrate as bctoolbox
        // rewrites each plain file; read-only ones are left alone.
        //
        // One-way, deliberately: a build without this cannot read the migrated files.
        VFS.enableAtStartup(context)
        if (VFS.isEnabled(context)) {
            VFS.setup(context)
        }

        val config = Factory.instance().createConfigWithFactory(
            corePreferences.configPath,
            corePreferences.factoryConfigPath
        )
        corePreferences.config = config

        // Clean-install guard: if a NEW build (different [build] token, stamped per build by the APK
        // Builder) is running on top of an OLD install's data, wipe our data now — BEFORE the Core
        // starts — so the new build provisions fresh (and re-prompts for the locked password). The OS
        // force-stops us right after; the user re-opens once. Abort the rest of startup in that case.
        if (corePreferences.wipeDataIfNewBuildInstalled()) {
            Log.w("$TAG New build detected over old data — wiped app data, awaiting clean restart")
            wakeLock.release()
            return
        }

        val appName = context.getString(R.string.app_name)
        Factory.instance().setLoggerDomain(appName)
        Factory.instance().loggingService.setLogLevel(LogLevel.Message)
        Factory.instance().enableLogcatLogs(corePreferences.printLogsInLogcat)

        Log.i("$TAG Report Core preferences initialized")
        Compatibility.setupAppStartupListener(context)

        coreContext = CoreContext(context)
        coreContext.start()

        ContextCompat.registerReceiver(
            this,
            ManagedConfigurationReceiver(),
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        DynamicColors.applyToActivitiesIfAvailable(this)
        wakeLock.release()
    }

    override fun onTrimMemory(level: Int) {
        Log.w("$TAG onTrimMemory called with level [${trimLevelToString(level)}]($level) !")
        when (level) {
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE -> {
                Log.i("$TAG Memory trim required, clearing imageLoader memory cache")
                imageLoader.memoryCache?.clear()
            }
            else -> {}
        }
        super.onTrimMemory(level)
    }

    override fun newImageLoader(context: Context): ImageLoader {
        // When VFS is enabled, prevent Coil from keeping plain version of files on disk
        val diskCachePolicy = if (VFS.isEnabled(applicationContext)) {
            CachePolicy.DISABLED
        } else {
            CachePolicy.ENABLED
        }

        return ImageLoader.Builder(this)
            .crossfade(false)
            .components {
                add(VideoFrameDecoder.Factory())
                // add(GifDecoder.Factory) // Do not add it, GIFs are properly rendered without it and adding it breaks resizing...
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                val cache = cacheDir.resolve("image_cache")
                DiskCache.Builder()
                    .directory(cache)
                    .maxSizePercent(0.02)
                    .build()
            }
            .networkCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(diskCachePolicy)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    private fun trimLevelToString(level: Int): String {
        return when (level) {
            TRIM_MEMORY_UI_HIDDEN -> "Hidden UI"
            TRIM_MEMORY_RUNNING_MODERATE -> "Moderate (Running)"
            TRIM_MEMORY_RUNNING_LOW -> "Low"
            TRIM_MEMORY_RUNNING_CRITICAL -> "Critical"
            TRIM_MEMORY_BACKGROUND -> "Background"
            TRIM_MEMORY_MODERATE -> "Moderate"
            TRIM_MEMORY_COMPLETE -> "Complete"
            else -> level.toString()
        }
    }
}
