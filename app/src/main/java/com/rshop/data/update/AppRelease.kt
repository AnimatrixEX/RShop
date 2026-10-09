package com.rshop.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A published version of RShop, as the repository's latest release describes it. */
data class AppRelease(
    /** "0.1.5": the tag without its leading "v". */
    val version: String,
    val tag: String,
    val notes: String?,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 published by GitHub for the file, when it gives one. */
    val sha256: String?,
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    val size: Long = 0,
    val digest: String? = null,
    @SerialName("browser_download_url") val url: String,
)

/** The release answer could not be used. */
sealed class ReleaseParseException(message: String) : Exception(message) {
    /** The release has no APK file. */
    class NoApk(val tag: String) : ReleaseParseException("Release $tag has no APK")

    /** The answer is not a release, or its version or file link is not usable. */
    class Invalid(detail: String) : ReleaseParseException(detail)
}

object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Hosts the APK may be downloaded from; GitHub then redirects to its own file servers. */
    private val allowedHosts = setOf("github.com")

    fun parse(body: String): AppRelease {
        val release = try {
            json.decodeFromString(GithubRelease.serializer(), body)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ReleaseParseException.Invalid("unreadable release: ${e.message?.take(120)}")
        }
        if (release.draft || release.prerelease) throw ReleaseParseException.Invalid("release ${release.tag} is not final")
        val version = release.tag.trim().removePrefix("v").removePrefix("V")
        if (AppVersion.parse(version) == null) throw ReleaseParseException.Invalid("tag '${release.tag}' is not a version")

        val asset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: throw ReleaseParseException.NoApk(release.tag)
        val url = asset.url.toHttpUrlOrNull()
        if (url == null || url.scheme != "https" || url.host !in allowedHosts) {
            throw ReleaseParseException.Invalid("APK link '${asset.url.take(80)}' is not an https github.com link")
        }
        if (asset.size <= 0) throw ReleaseParseException.Invalid("APK ${asset.name} has no size")
        val sha = asset.digest?.removePrefix("sha256:")?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdefABCDEF" } }
        return AppRelease(
            version = version,
            tag = release.tag,
            notes = release.body?.trim()?.takeIf { it.isNotEmpty() },
            apkUrl = url.toString(),
            apkName = asset.name,
            sizeBytes = asset.size,
            sha256 = sha?.lowercase(),
        )
    }
}

/** Dotted numeric version ("0.1.5", "1.2"); anything after a '-' or '+' (pre-release, build) is ignored for ordering. */
class AppVersion private constructor(private val parts: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = (parts.getOrElse(i) { 0 }).compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0
    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()
    override fun toString(): String = parts.joinToString(".")

    companion object {
        fun parse(text: String): AppVersion? {
            val core = text.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            val parts = core.split('.').map { it.toIntOrNull() ?: return null }
            return if (parts.isEmpty() || parts.any { it < 0 }) null else AppVersion(parts)
        }
    }
}
