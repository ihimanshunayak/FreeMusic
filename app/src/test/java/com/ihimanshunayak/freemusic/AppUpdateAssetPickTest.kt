package com.ihimanshunayak.freemusic

import com.ihimanshunayak.freemusic.data.AppUpdateChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the release-asset selection the in-app updater depends on.
 *
 * GitHub returns release assets in name order, so a release holding a
 * universal build plus per-ABI splits lists `arm64-v8a` before `universal`.
 * Picking positionally would hand a 32-bit phone an arm64 split, which the
 * package installer rejects — these tests pin the device-aware behaviour.
 */
class AppUpdateAssetPickTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The real v1.7 asset set, in the order the GitHub API returns it. */
    private fun releaseAssets(
        order: List<String> = listOf(
            "app-prod-arm64-v8a-release.apk",
            "app-prod-armeabi-v7a-release.apk",
            "app-prod-universal-release.apk",
            "app-prod-x86_64-release.apk",
        ),
    ): List<JsonObject> = order.map { name ->
        json.parseToJsonElement(
            """{"name":"$name","state":"uploaded","browser_download_url":"https://example.test/$name"}""",
        ) as JsonObject
    }

    private fun pick(apks: List<JsonObject>, abis: Array<String>): String? =
        AppUpdateChecker.pickApkForAbis(apks, abis)
            ?.get("name")
            ?.jsonPrimitive
            ?.content

    @Test
    fun `arm64 device gets the arm64 split even though universal sorts after it`() {
        val picked = pick(releaseAssets(), arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals("app-prod-arm64-v8a-release.apk", picked)
    }

    @Test
    fun `32-bit device gets the armeabi-v7a split`() {
        val picked = pick(releaseAssets(), arrayOf("armeabi-v7a", "armeabi"))
        assertEquals("app-prod-armeabi-v7a-release.apk", picked)
    }

    @Test
    fun `x86_64 emulator gets its own split`() {
        val picked = pick(releaseAssets(), arrayOf("x86_64", "x86"))
        assertEquals("app-prod-x86_64-release.apk", picked)
    }

    @Test
    fun `unsupported abi falls back to the universal build`() {
        val picked = pick(releaseAssets(), arrayOf("mips64", "mips"))
        assertEquals("app-prod-universal-release.apk", picked)
    }

    @Test
    fun `single-apk release is used as-is`() {
        val picked = pick(releaseAssets(listOf("FreeMusic-universal.apk")), arrayOf("arm64-v8a"))
        assertEquals("FreeMusic-universal.apk", picked)
    }

    @Test
    fun `device abi preference order wins over asset order`() {
        // A device that lists both would still prefer its first entry.
        val picked = pick(
            releaseAssets(),
            arrayOf("x86_64", "arm64-v8a", "armeabi-v7a"),
        )
        assertEquals("app-prod-x86_64-release.apk", picked)
    }

    @Test
    fun `freshly uploaded asset is excluded until GitHub reports it uploaded`() {
        val assets = listOf(
            json.parseToJsonElement(
                """{"name":"app-prod-universal-release.apk","state":"starter","browser_download_url":"https://example.test/u"}""",
            ) as JsonObject,
            json.parseToJsonElement(
                """{"name":"app-prod-arm64-v8a-release.apk","state":"uploaded","browser_download_url":"https://example.test/a"}""",
            ) as JsonObject,
        )
        // The universal build is still mid-upload, so the arm64 split is the
        // only usable asset and wins — matching what the releases page shows.
        assertEquals("app-prod-arm64-v8a-release.apk", pick(assets, arrayOf("arm64-v8a")))
    }

    @Test
    fun `non-apk assets are ignored`() {
        val assets = listOf(
            json.parseToJsonElement(
                """{"name":"source.zip","state":"uploaded","browser_download_url":"https://example.test/s"}""",
            ) as JsonObject,
        )
        assertEquals(null, pick(assets, arrayOf("arm64-v8a")))
    }

    @Test
    fun `empty asset list yields no url`() {
        assertNull(AppUpdateChecker.pickApkForAbis(emptyList(), arrayOf("arm64-v8a")))
    }
}
