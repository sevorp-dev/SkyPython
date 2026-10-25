package dev.skyloft.skypython.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the report collects is reviewed in code: none of these APIs or permissions may appear
 * in the report package or the manifest. (JVM tests run from the module directory.)
 */
class PrivacyTest {

    private val sources = File("src/main/java/dev/skyloft/skypython/report").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private val forbiddenApis = listOf(
        "getDeviceId", "getImei", "getMeid", "getLine1Number", "getSubscriberId", "getSimSerialNumber",
        "Settings.Secure.ANDROID_ID", "ANDROID_ID", "AdvertisingIdClient", "AccountManager",
        "LocationManager", "FusedLocation", "ContactsContract", "Build.getSerial", "Build.SERIAL",
        "SubscriptionManager", "getAllCellInfo", "WifiInfo", "getMacAddress", "getSSID",
    )

    @Test fun `the report package exists`() {
        assertTrue(sources.map { it.name }.toString(), sources.any { it.name == "DeviceInfo.kt" })
    }

    @Test fun `no device identifiers, accounts, location or contacts are read`() {
        for (file in sources) {
            val code = file.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
            for (api in forbiddenApis) {
                assertFalse("${file.name} uses $api", api in code)
            }
        }
    }

    @Test fun `no dangerous permissions are requested`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (permission in listOf("READ_PHONE_STATE", "READ_PHONE_NUMBERS", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "GET_ACCOUNTS", "READ_CONTACTS", "AD_ID")) {
            assertFalse(permission, permission in manifest)
        }
    }

    @Test fun `forbidden keys are recognised`() {
        for (key in listOf("IMEI", "Phone number", "Android ID", "Advertising ID", "Serial", "Account", "Location", "Contacts", "IMSI", "ICCID", "MAC address")) {
            assertTrue(key, Privacy.isForbiddenKey(key))
        }
        for (key in listOf("Model", "Network operator", "SIM operator", "Network country", "Phone type", "Battery", "Locales", "Time zone")) {
            assertFalse(key, Privacy.isForbiddenKey(key))
        }
    }
}
