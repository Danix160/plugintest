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

        val options = document.select("li.dooplay_player_option")
        val fallbackPost = document.selectFirst("div#player")?.attr("data-post") 
            ?: document.selectFirst("link[rel='shortlink']")?.attr("href")?.substringAfter("p=")

        if (options.isEmpty() && fallbackPost != null) {
            if (fetchDooPlayAjax(fallbackPost, "1", "0", data, subtitleCallback, callback)) foundAny = true
        }

        options.forEach { option ->
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")
            if (fetchDooPlayAjax(post, nume, type, data, subtitleCallback, callback)) foundAny = true
        }

        return foundAny
    }

    private suspend fun fetchDooPlayAjax(
        post: String,
        nume: String,
        type: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to type
                ),
                referer = referer,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text

            val iframeUrl = Regex("""(?:src|href)\s*[:=]\s*["']([^"']+)["']""").find(response)?.groupValues?.get(1)
                ?: Regex("""https?://[^\s"']+""").find(response)?.value

            iframeUrl?.let { 
                processFinalUrl(it, referer, subtitleCallback, callback)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun processFinalUrl(
        url: String, 
        referer: String, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = url.replace("\\/", "/").let { if (it.startsWith("//")) "https:$it" else it }
        
        return if (cleanUrl.contains("server1.uns.bio") || cleanUrl.contains("vidstack")) {
            VidStack().getUrl(cleanUrl, referer, subtitleCallback, callback)
            true
        } else {
            loadExtractor(cleanUrl, referer, subtitleCallback, callback)
        }
    }
}

// =============================================================================
// ESTRATTORE: VidStack (Corretto per compilazione)
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

        val response = app.get("$baseurl/api/v1/video?id=$hash", headers = headers)
        if (response.code != 200) return
        val encoded = response.text.trim()

        val key = "kiemtienmua911ca"
        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
            try { AesHelper.decryptAES(encoded, key, iv) } catch (e: Exception) { null }
        } ?: return

        Regex("\"source\":\"(.*?)\"").find(decryptedText)?.groupValues?.get(1)?.replace("\\/", "/")?.let { m3u8 ->
            // FIX: Utilizzo corretto di newExtractorLink per evitare l'errore di tipi
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = url
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

object AesHelper {
    fun decryptAES(inputHex: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        val secretKey = SecretKeySpec(key.toByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray())
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decodedHex = inputHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(cipher.doFinal(decodedHex))
    }
}
