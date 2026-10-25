package dev.skyloft.skypython.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipShowTest {

    @Test
    fun parsesPipShow() {
        val out = """
            Name: requests
            Version: 2.32.3
            Summary: Python HTTP for Humans.
            Home-page: https://requests.readthedocs.io
            Author: Kenneth Reitz
            Author-email: me@kennethreitz.org
            License: Apache-2.0
            Location: /data/user/0/dev.skyloft.skypython/files/home/.local/lib/python3.14/site-packages
            Requires: certifi, charset-normalizer, idna, urllib3
            Required-by: httpie
        """.trimIndent() + "\n"
        assertEquals(
            PackageDetails(
                name = "requests",
                version = "2.32.3",
                summary = "Python HTTP for Humans.",
                homePage = "https://requests.readthedocs.io",
                license = "Apache-2.0",
                requires = listOf("certifi", "charset-normalizer", "idna", "urllib3"),
                requiredBy = listOf("httpie"),
            ),
            PipShow.parse(out),
        )
    }

    @Test
    fun emptyFieldsAndMultiLineLicense() {
        val out = "WARNING: x\nName: six\nVersion: 1.17.0\nSummary: \nHome-page: \n" +
            "License: Copyright (c) 2010\n\nPermission is hereby granted\nLicense-Expression: \n" +
            "Location: /x\nRequires: \nRequired-by: \n"
        val d = PipShow.parse(out)!!
        assertNull(d.summary)
        assertNull(d.homePage)
        assertEquals("Copyright (c) 2010", d.license)
        assertEquals(emptyList<String>(), d.requires)
        assertEquals(emptyList<String>(), d.requiredBy)
    }

    @Test
    fun licenseExpressionWins() {
        val d = PipShow.parse("Name: a\nVersion: 1\nLicense: \nLicense-Expression: MIT\n")!!
        assertEquals("MIT", d.license)
    }

    @Test
    fun noNameIsNull() {
        assertNull(PipShow.parse("WARNING: Package(s) not found: nosuch\n"))
    }
}
