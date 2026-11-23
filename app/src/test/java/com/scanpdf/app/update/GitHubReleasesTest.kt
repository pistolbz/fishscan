package com.scanpdf.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleasesTest {

    private val sha = "6845023258f88f31a95b8fdcdcfc1c69849ac66ea3ed14fe0c4b38332281374e"

    /** Shaped like the real `GET /repos/{owner}/{repo}/releases` response. */
    private val json = """
        [
          {"tag_name": "v0.4.0", "name": "Draft", "draft": true, "prerelease": false, "body": null,
           "assets": [{"name": "ScanPDF-v0.4.0.apk", "size": 1, "digest": null,
                       "browser_download_url": "https://github.com/o/r/releases/download/v0.4.0/ScanPDF-v0.4.0.apk"}]},
          {"tag_name": "v0.3.0", "name": "", "draft": false, "prerelease": true,
           "body": "Thử nghiệm\nSHA-256: `${sha.uppercase()}`",
           "assets": [{"name": "notes.txt", "size": 10, "digest": null, "browser_download_url": "https://x/notes.txt"},
                      {"name": "ScanPDF-v0.3.0.apk", "size": 24594412, "digest": null,
                       "browser_download_url": "https://github.com/o/r/releases/download/v0.3.0/ScanPDF-v0.3.0.apk"}]},
          {"tag_name": "v0.2.1", "name": "Sửa lỗi", "draft": false, "prerelease": false, "body": "- sửa A",
           "assets": [{"name": "ScanPDF-v0.2.1.apk", "size": 24000000, "digest": "sha256:$sha",
                       "browser_download_url": "https://github.com/o/r/releases/download/v0.2.1/ScanPDF-v0.2.1.apk"}]},
          {"tag_name": "v0.2.5", "name": "No APK", "draft": false, "prerelease": false, "body": "", "assets": []},
          {"tag_name": "nightly", "name": "Not semver", "draft": false, "prerelease": false, "body": "",
           "assets": [{"name": "a.apk", "size": 1, "digest": null, "browser_download_url": "https://x/a.apk"}]},
          {"tag_name": "v0.2.9", "name": "Plain http", "draft": false, "prerelease": false, "body": "",
           "assets": [{"name": "a.apk", "size": 1, "digest": null, "browser_download_url": "http://x/a.apk"}]},
          {"tag_name": "v0.1.0", "name": "", "draft": false, "prerelease": false, "body": "",
           "assets": [{"name": "ScanPDF-v0.1.0.apk", "size": 24594412, "digest": "sha256:$sha",
                       "browser_download_url": "https://github.com/o/r/releases/download/v0.1.0/ScanPDF-v0.1.0.apk"}]}
        ]
    """.trimIndent()

    @Test
    fun parseKeepsOnlyPublishedSemverReleasesWithHttpsApk() {
        val releases = GitHubReleases.parse(json)
        assertEquals(listOf("v0.3.0", "v0.2.1", "v0.1.0"), releases.map { it.tag })
    }

    @Test
    fun parseReadsAssetDigestAndFallsBackToNotes() {
        val byTag = GitHubReleases.parse(json).associateBy { it.tag }
        assertEquals(sha, byTag.getValue("v0.2.1").apk.sha256)
        assertEquals(sha, byTag.getValue("v0.3.0").apk.sha256) // from "SHA-256: `...`" in notes, lower-cased
        assertEquals("ScanPDF-v0.3.0.apk", byTag.getValue("v0.3.0").apk.name) // skips non-APK asset
        assertEquals("v0.3.0", byTag.getValue("v0.3.0").title) // empty name → tag
        assertEquals("Sửa lỗi", byTag.getValue("v0.2.1").title)
        assertTrue(byTag.getValue("v0.3.0").prerelease)
    }

    @Test
    fun pickUpdateRespectsPrereleaseSetting() {
        val releases = GitHubReleases.parse(json)
        val current = SemVer(0, 2, 0)
        assertEquals("v0.3.0", GitHubReleases.pickUpdate(releases, current, includePrereleases = true)?.tag)
        assertEquals("v0.2.1", GitHubReleases.pickUpdate(releases, current, includePrereleases = false)?.tag)
    }

    @Test
    fun pickUpdateReturnsNullWhenUpToDate() {
        val releases = GitHubReleases.parse(json)
        assertNull(GitHubReleases.pickUpdate(releases, SemVer(0, 3, 0), includePrereleases = true))
        assertNull(GitHubReleases.pickUpdate(releases, SemVer(1, 0, 0), includePrereleases = true))
        assertNull(GitHubReleases.pickUpdate(emptyList(), SemVer(0, 1, 0), includePrereleases = true))
    }

    @Test
    fun emptyOrDraftOnlyListGivesNoReleases() {
        assertTrue(GitHubReleases.parse("[]").isEmpty())
    }

    @Test
    fun semVerParsingAndOrdering() {
        assertEquals(SemVer(0, 2, 0), SemVer.parse("v0.2.0"))
        assertEquals(SemVer(1, 2, 0), SemVer.parse("1.2"))
        assertEquals(SemVer(3, 0, 0), SemVer.parse("V3"))
        assertEquals(SemVer(1, 4, 2), SemVer.parse("1.4.2-beta.1"))
        assertNull(SemVer.parse("nightly"))
        assertNull(SemVer.parse("1.2.3.4"))
        assertNull(SemVer.parse("1.-2.0"))
        assertTrue(SemVer(0, 10, 0) > SemVer(0, 9, 9)) // numeric, not text comparison
        assertTrue(SemVer(1, 0, 0) > SemVer(0, 99, 99))
    }

    @Test
    fun hexIsLowerCaseAndLocaleIndependent() {
        assertEquals("00ff10ab", UpdateRepository.toHex(byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0xAB.toByte())))
    }
}
