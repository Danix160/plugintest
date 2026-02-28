package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// =============================================================================
// PROVIDER PRINCIPALE: GuardaPlay
// =============================================================================

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/commedia/" to "Commedia",
        "$mainUrl/category/fantascienza/" to "Fantascienza",
        "$mainUrl/category/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("article.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.movies").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("//")) "https:$it" else it 
        }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")?.let {
            if (it.startsWith("//")) "https:$it" else it
        }
        val plot = document.selectFirst(".description p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundAny = false

        document.select("li.dooplay_player_option").forEach { option ->
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")

            if (post.isNotBlank()) {
                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text

                val iframeUrl = Regex("""<iframe.*?src=["'](.*?)["']""").find(response)?.groupValues?.get(1)
                    ?: Regex("""https?://[^\s"']+""").find(response)?.value

                iframeUrl?.let { 
                    if (processFinalUrl(it, data, subtitleCallback, callback)) foundAny = true 
                }
            }
        }
        return foundAny
    }

    private suspend fun processFinalUrl(
        url: String, 
        referer: String, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = url.replace("\\/", "/").let { if (it.startsWith("//")) "https:$it" else it }
        
        return when {
            cleanUrl.contains("server1.uns.bio") || cleanUrl.contains("vidstack") -> {
                Server1uns().getUrl(cleanUrl, referer, subtitleCallback, callback)
                true
            }
            else -> loadExtractor(cleanUrl, referer, subtitleCallback, callback)
        }
    }
}

// =============================================================================
// ESTRATTORE: VidStack con fix per newExtractorLink
// =============================================================================

open class VidStack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://vidstack.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        val hash = url.substringAfterLast("#").substringAfter("/")
        val baseurl = try { URI(url).let { "${it.scheme}://${it.host}" } } catch(e: Exception) { mainUrl }

        val encoded = app.get("$baseurl/api/v1/video?id=$hash", headers = headers).text.trim()

        val key = "kiemtienmua911ca"
        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
            try { AesHelper.decryptAES(encoded, key, iv) } catch (e: Exception) { null }
        } ?: return

        Regex("\"source\":\"(.*?)\"").find(decryptedText)?.groupValues?.get(1)?.replace("\\/", "/")?.let { m3u8 ->
            // FIX DEFINITIVO: Parametri corretti per l'SDK Cloudstream
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                }
            )
        }

        val subtitleSection = Regex("\"subtitle\":\\{(.*?)\\}").find(decryptedText)?.groupValues?.get(1)
        subtitleSection?.let { section ->
            Regex("\"([^\"]+)\":\\s*\"([^\"]+)\"").findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val path = match.groupValues[2].split("#")[0].replace("\\/", "/")
                if (path.isNotEmpty()) {
                    subtitleCallback(newSubtitleFile(lang, fixUrl("$baseurl$path")))
                }
            }
        }
    }
}

class Server1uns : VidStack() {
    override var name = "Vidstack"
    override var mainUrl = "https://server1.uns.bio"
}

// =============================================================================
// HELPER PER DECRIPTAZIONE AES
// =============================================================================

object AesHelper {
    fun decryptAES(inputHex: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        val secretKey = SecretKeySpec(key.toByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray())
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        
        // Converte Hex in ByteArray
        val decodedHex = inputHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decryptedBytes = cipher.doFinal(decodedHex)
        return String(decryptedBytes)
    }
}
