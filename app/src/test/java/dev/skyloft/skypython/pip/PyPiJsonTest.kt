package dev.skyloft.skypython.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PyPiJsonTest {

    private fun doc(info: String, urls: String = "[]", extra: String = "") =
        """{"info": $info, "last_serial": 1, $extra "urls": $urls, "vulnerabilities": []}"""

    private fun file(name: String, type: String = "bdist_wheel") =
        """{"filename": "$name", "packagetype": "$type", "size": 1}"""

    @Test
    fun parsesTheBasics() {
        val json = doc(
            """{"name": "requests", "version": "2.32.3", "summary": "Python HTTP for Humans.",
               "home_page": "https://requests.readthedocs.io", "license": "Apache-2.0",
               "project_urls": null, "requires_dist": ["idna<4,>=2.5"]}""",
            "[${file("requests-2.32.3-py3-none-any.whl")}, ${file("requests-2.32.3.tar.gz", "sdist")}]",
        )
        assertEquals(
            PyPiProject(
                name = "requests",
                version = "2.32.3",
                summary = "Python HTTP for Humans.",
                homePage = "https://requests.readthedocs.io",
                license = "Apache-2.0",
                wheel = WheelSupport.Pure,
            ),
            PyPiJson.parse(json),
        )
    }

    @Test
    fun skipsTheHugeReleasesMap() {
        val json = """{"info": {"name": "x", "version": "1"}, "releases": {"0.1": [{"filename": "x-0.1-py3-none-any.whl"}],
            "0.2": [[1, 2, {"nested": [true, false, null, 1.5e3]}]]},
            "urls": [${file("x-1.tar.gz", "sdist")}]}"""
        val p = PyPiJson.parse(json)!!
        assertEquals("x", p.name)
        assertEquals(WheelSupport.SourceOnly, p.wheel)
    }

    @Test
    fun missingOptionalFieldsAreNull() {
        val p = PyPiJson.parse(doc("""{"name": "a", "version": "1", "summary": "", "home_page": null, "license": null}"""))!!
        assertNull(p.summary)
        assertNull(p.homePage)
        assertNull(p.license)
        assertEquals(WheelSupport.NoFiles, p.wheel)
    }

    @Test
    fun homePageFallsBackToProjectUrlsThenThePackagePage() {
        val fromUrls = PyPiJson.parse(
            doc(
                """{"name": "a", "version": "1", "home_page": "",
                   "project_urls": {"Changelog": "https://c", "Source": "https://s", "Homepage": "https://h"},
                   "package_url": "https://pypi.org/project/a/"}""",
            ),
        )!!
        assertEquals("https://h", fromUrls.homePage)
        val sourceOnly = PyPiJson.parse(
            doc("""{"name": "a", "version": "1", "project_urls": {"Source": "https://s"}}"""),
        )!!
        assertEquals("https://s", sourceOnly.homePage)
        val page = PyPiJson.parse(
            doc("""{"name": "a", "version": "1", "package_url": "https://pypi.org/project/a/"}"""),
        )!!
        assertEquals("https://pypi.org/project/a/", page.homePage)
    }

    @Test
    fun licensePrefersExpressionThenShortTextThenClassifier() {
        val expr = PyPiJson.parse(
            doc("""{"name": "a", "version": "1", "license_expression": "MIT", "license": "long text"}"""),
        )!!
        assertEquals("MIT", expr.license)
        val longText = "Copyright (c) 2010 Someone\n\nPermission is hereby granted, free of charge, " + "x".repeat(500)
        val fromClassifier = PyPiJson.parse(
            doc(
                """{"name": "a", "version": "1", "license": ${com.google.gson.Gson().toJson(longText)},
                   "classifiers": ["Programming Language :: Python", "License :: OSI Approved :: BSD License"]}""",
            ),
        )!!
        assertEquals("BSD License", fromClassifier.license)
        val firstLine = PyPiJson.parse(doc("""{"name": "a", "version": "1", "license": ${com.google.gson.Gson().toJson(longText)}}"""))!!
        assertEquals("Copyright (c) 2010 Someone", firstLine.license)
    }

    @Test
    fun malformedOrIncompleteIsNull() {
        assertNull(PyPiJson.parse("not json"))
        assertNull(PyPiJson.parse("[]"))
        assertNull(PyPiJson.parse("""{"info": {"name": "a"}, "urls": []}"""))
        assertNull(PyPiJson.parse("""{"urls": []}"""))
        assertNull(PyPiJson.parse("""{"info": {"name": "a", "version": "1"}, "urls": [}"""))
    }

    @Test
    fun wheelSupportClassification() {
        assertEquals(WheelSupport.Pure, PyPiJson.wheelSupport(listOf("six-1.17.0-py2.py3-none-any.whl", "six-1.17.0.tar.gz")))
        assertEquals(
            WheelSupport.PrebuiltAndroid,
            PyPiJson.wheelSupport(
                listOf(
                    "markupsafe-3.0.4-cp314-cp314-android_24_arm64_v8a.whl",
                    "markupsafe-3.0.4-cp314-cp314-win_amd64.whl",
                    "markupsafe-3.0.4.tar.gz",
                ),
            ),
        )
        assertEquals(WheelSupport.PrebuiltAndroid, PyPiJson.wheelSupport(listOf("x-1-cp39-abi3-android_24_x86_64.whl")))
        // Android wheels for another Python only.
        assertEquals(
            WheelSupport.NativeOnly,
            PyPiJson.wheelSupport(listOf("x-1-cp313-cp313-android_24_arm64_v8a.whl", "x-1.tar.gz")),
        )
        assertEquals(
            WheelSupport.NativeOnly,
            PyPiJson.wheelSupport(listOf("PyYAML-6.0.3-cp314-cp314-manylinux_2_17_x86_64.whl", "pyyaml-6.0.3.tar.gz")),
        )
        assertEquals(WheelSupport.SourceOnly, PyPiJson.wheelSupport(listOf("art-6.5.tar.gz")))
        assertEquals(WheelSupport.SourceOnly, PyPiJson.wheelSupport(listOf("old-1.0.zip")))
        assertEquals(WheelSupport.NoFiles, PyPiJson.wheelSupport(emptyList()))
    }

    @Test
    fun normalizesNamesLikePep503() {
        assertEquals("python-dateutil", PyPiJson.normalize("Python_DateUtil"))
        assertEquals("zope-interface", PyPiJson.normalize("zope.interface"))
        assertEquals("a-b", PyPiJson.normalize("a__-.b"))
    }
}
