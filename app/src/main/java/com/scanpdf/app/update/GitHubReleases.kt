package com.scanpdf.app.update

import org.json.JSONArray
import org.json.JSONObject

/** "v1.2.3", "1.2", "1.2.3-beta" → comparable numeric version (suffixes are ignored). */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(text: String): SemVer? {
            val core = text.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            return SemVer(numbers[0], numbers.getOrElse(1) { 0 }, numbers.getOrElse(2) { 0 })
        }
    }
}

data class ApkAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    /** Lower-case hex, or null when GitHub did not provide one. */
    val sha256: String?,
)

data class ReleaseInfo(
    val tag: String,
    val version: SemVer,
    val title: String,
    val notes: String,
    val prerelease: Boolean,
    val apk: ApkAsset,
)

/** Reads the GitHub "list releases" API response. Pure logic, unit-tested. */
object GitHubReleases {

    private val bodySha = Regex("SHA-256:\\s*`?([0-9a-fA-F]{64})`?")

    /** Published (non-draft) releases that have a semantic-version tag and an .apk asset. */
    fun parse(json: String): List<ReleaseInfo> {
        val array = JSONArray(json)
        val out = ArrayList<ReleaseInfo>()
        for (i in 0 until array.length()) {
            val release = array.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            val tag = release.text("tag_name") ?: continue
            val version = SemVer.parse(tag) ?: continue
            val notes = release.text("body").orEmpty().trim()
            val apk = findApk(release.optJSONArray("assets"), notes) ?: continue
            out += ReleaseInfo(
                tag = tag,
                version = version,
                title = release.text("name") ?: tag,
                notes = notes,
                prerelease = release.optBoolean("prerelease", false),
                apk = apk,
            )
        }
        return out
    }

    /** Highest release newer than [current], or null when already up to date. */
    fun pickUpdate(releases: List<ReleaseInfo>, current: SemVer, includePrereleases: Boolean): ReleaseInfo? =
        releases
            .filter { includePrereleases || !it.prerelease }
            .filter { it.version > current }
            .maxByOrNull { it.version }

    private fun findApk(assets: JSONArray?, notes: String): ApkAsset? {
        if (assets == null) return null
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            val name = asset.text("name") ?: continue
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.text("browser_download_url") ?: continue
            if (!url.startsWith("https://")) continue
            // GitHub computes "sha256:<hex>" for every uploaded asset; release notes are a fallback.
            val sha = asset.text("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                ?: bodySha.find(notes)?.groupValues?.get(1)
            return ApkAsset(name, asset.optLong("size", -1), url, sha?.lowercase())
        }
        return null
    }

    /** optString() turns JSON null into the text "null"; this returns real null instead. */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
