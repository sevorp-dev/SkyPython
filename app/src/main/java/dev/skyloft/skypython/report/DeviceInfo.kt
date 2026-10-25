package dev.skyloft.skypython.report

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.telephony.TelephonyManager
import android.view.Display
import android.webkit.WebView
import dev.skyloft.skypython.locale.AppLocale
import dev.skyloft.skypython.pip.PipRepository
import dev.skyloft.skypython.settings.EditorSettings
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * What the report says about the phone and the app. Only what needs no dangerous permission
 * and identifies neither the person nor the device: no IMEI, phone number, Android ID,
 * advertising ID, serial, accounts, location or contacts (see `PrivacyTest`), and nothing
 * from the user's files. Every entry is read on its own: one failing read (a vendor's
 * SecurityException, a missing service) only drops that entry.
 */
object DeviceInfo {

    /** "0.0.3 (32), debug". */
    fun appVersion(context: Context): String {
        val info = packageInfo(context, context.packageName)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else info?.versionCode?.toLong()
        return "${info?.versionName ?: "?"} (${code ?: "?"}), ${buildType(context)}"
    }

    /** Reads everything; slow-ish (binder calls), so call it off the main thread. */
    fun collect(context: Context, settings: EditorSettings?): List<InfoSection> = listOf(
        app(context),
        system(),
        device(),
        memory(context),
        screen(context),
        locale(context),
        battery(context),
        network(context),
        operator(context),
        webView(context),
        python(context),
        settings(settings),
    ).filter { it.entries.isNotEmpty() }

    private class Builder(val title: String) {
        val entries = mutableListOf<InfoEntry>()

        fun add(key: String, read: () -> Any?) {
            val value = try {
                read()?.toString()?.takeIf { it.isNotBlank() }
            } catch (e: Exception) { // SecurityException on some vendors, missing services…
                "unavailable (${e.javaClass.simpleName})"
            } ?: return
            entries += InfoEntry(key, value)
        }

        fun build() = InfoSection(title, entries)
    }

    private fun section(title: String, block: Builder.() -> Unit) = Builder(title).apply(block).build()

    private fun app(context: Context) = section("App") {
        add("Version") { appVersion(context) }
        add("Package") { context.packageName }
        add("Installer") { installer(context) }
        add("Target SDK") { context.applicationInfo.targetSdkVersion }
        add("Native libs extracted") { File(context.applicationInfo.nativeLibraryDir).isDirectory }
    }

    private fun system() = section("System") {
        add("Android") { Build.VERSION.RELEASE }
        add("SDK") { Build.VERSION.SDK_INT }
        add("Security patch") { if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else null }
        add("Build") { Build.DISPLAY }
        add("Fingerprint") { Build.FINGERPRINT }
        add("Build type / tags") { "${Build.TYPE} / ${Build.TAGS}" }
        add("Kernel") { System.getProperty("os.version") }
        add("Runtime") { "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}" }
    }

    private fun device() = section("Device") {
        add("Manufacturer") { Build.MANUFACTURER }
        add("Brand") { Build.BRAND }
        add("Model") { Build.MODEL }
        add("Device") { Build.DEVICE }
        add("Product") { Build.PRODUCT }
        add("Board") { Build.BOARD }
        add("Hardware") { Build.HARDWARE }
        add("SoC") { if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else null }
        add("ABIs") { Build.SUPPORTED_ABIS.joinToString() }
        add("CPU cores") { Runtime.getRuntime().availableProcessors() }
    }

    private fun memory(context: Context) = section("Memory and storage") {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        add("Total RAM") { bytes(mem.totalMem) }
        add("Available RAM") { bytes(mem.availMem) }
        add("Low memory now") { mem.lowMemory }
        add("Low-RAM device") { am?.isLowRamDevice }
        add("App memory class") { am?.memoryClass?.let { "$it MB (large: ${am.largeMemoryClass} MB)" } }
        add("App heap") { "${bytes(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())} used of ${bytes(Runtime.getRuntime().maxMemory())}" }
        add("Internal storage") {
            val fs = StatFs(context.filesDir.path)
            "${bytes(fs.availableBytes)} free of ${bytes(fs.totalBytes)}"
        }
    }

    private fun screen(context: Context) = section("Screen") {
        val dm = context.resources.displayMetrics
        val config = context.resources.configuration
        add("Size") { "${dm.widthPixels}×${dm.heightPixels} px, ${config.screenWidthDp}×${config.screenHeightDp} dp" }
        add("Density") { "${dm.densityDpi} dpi (×${dm.density})" }
        add("Font scale") { config.fontScale }
        add("Refresh rate") {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate?.let { "%.0f Hz".format(Locale.US, it) }
        }
        add("Orientation") { if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait" }
        add("Night mode") { (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
    }

    private fun locale(context: Context) = section("Language and region") {
        add("App language") { AppLocale.current(context).name }
        add("System locales") {
            if (Build.VERSION.SDK_INT >= 24) android.content.res.Resources.getSystem().configuration.locales.toLanguageTags()
            else Locale.getDefault().toLanguageTag()
        }
        add("Time zone") { TimeZone.getDefault().id }
    }

    private fun battery(context: Context) = section("Battery") {
        val bm = context.getSystemService(BatteryManager::class.java)
        add("Level") { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }?.let { "$it%" } }
        add("Charging") { if (Build.VERSION.SDK_INT >= 23) bm?.isCharging else null }
        add("Battery saver") { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode }
    }

    private fun network(context: Context) = section("Network") {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        add("Connection") {
            val caps = cm?.activeNetwork?.let(cm::getNetworkCapabilities)
            when {
                caps == null -> "none"
                else -> listOfNotNull(
                    "Wi-Fi".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                    "cellular".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                    "Ethernet".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
                    "Bluetooth".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) },
                    "VPN".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
                ).ifEmpty { listOf("other") }.joinToString(" + ") +
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) ", internet" else ", no internet")
            }
        }
        add("Metered") { cm?.isActiveNetworkMetered }
    }

    private fun operator(context: Context) = section("Mobile operator") {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return@section
        add("Network operator") { tm.networkOperatorName }
        add("Network operator code") { tm.networkOperator } // MCC+MNC: the carrier, not the subscriber
        add("Network country") { tm.networkCountryIso.uppercase(Locale.US) }
        add("SIM operator") { tm.simOperatorName }
        add("SIM country") { tm.simCountryIso.uppercase(Locale.US) }
        add("SIM state") { simState(tm.simState) }
        add("Phone type") { phoneType(tm.phoneType) }
        add("Roaming") { tm.isNetworkRoaming }
        add("Active modems") { if (Build.VERSION.SDK_INT >= 30) tm.activeModemCount else null }
    }

    private fun webView(context: Context) = section("Browser components") {
        add("WebView") {
            val pkg = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else packageInfo(context, "com.google.android.webview")
            pkg?.let { "${it.packageName} ${it.versionName}" }
        }
        add("Chrome") { packageInfo(context, "com.android.chrome")?.versionName }
    }

    private fun python(context: Context) = section("Python") {
        add("Bundled Python") { dev.skyloft.skypython.runtime.BuildConfig.PYTHON_VERSION }
        add("Runtime installed") {
            val marker = File(context.filesDir, "python/.installed")
            if (marker.isFile) "yes (${marker.readText().trim()})" else "no"
        }
        add("Language server installed") { File(context.filesDir, "langserver/.installed").isFile }
        val userBase = File(context.filesDir, "home/.local")
        add("pip set up") { File(userBase, PipRepository.MARKER).isFile }
        add("pip packages") {
            File(userBase, "lib").listFiles().orEmpty()
                .flatMap { File(it, "site-packages").listFiles().orEmpty().toList() }
                .count { it.name.endsWith(".dist-info") }
        }
    }

    private fun settings(settings: EditorSettings?) = section("App settings") {
        if (settings == null) return@section
        add("Font size") { "${settings.fontSizeSp} sp" }
        add("Word wrap") { settings.wordWrap }
        add("Line numbers") { settings.lineNumbers }
        add("Code completion") { settings.codeCompletion }
        add("Auto-close brackets") { settings.autoClosePairs }
        add("Keyboard suggestions") { settings.keyboardSuggestions }
        add("Keep screen on") { settings.keepScreenOn }
        add("Symbol keys") { if (settings.symbolKeys == EditorSettings().symbolKeys) "default" else "customised" }
    }

    private fun buildType(context: Context) =
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release"

    private fun installer(context: Context): String? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        } ?: "unknown (sideloaded)"
    }

    private fun packageInfo(context: Context, name: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(name, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun simState(state: Int) = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "absent"
        TelephonyManager.SIM_STATE_READY -> "ready"
        TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED -> "locked"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
        else -> "unknown ($state)"
    }

    private fun phoneType(type: Int) = when (type) {
        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
        TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
        TelephonyManager.PHONE_TYPE_SIP -> "SIP"
        TelephonyManager.PHONE_TYPE_NONE -> "none"
        else -> "unknown ($type)"
    }

    private fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> "%.1f GB".format(Locale.US, n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> "%.0f MB".format(Locale.US, n / (1L shl 20).toDouble())
        else -> "$n B"
    }
}
