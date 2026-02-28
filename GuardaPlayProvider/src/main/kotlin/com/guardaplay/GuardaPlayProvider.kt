package com.guardaplay

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    private val clientUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

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
        val home = document.select("li.movies, article.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("li.movies, article.movies").mapNotNull { it.toSearchResult() }
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
        val pUrl = document.selectFirst(".post-thumbnail img")?.attr("src")
        val poster = if (pUrl?.startsWith("//") == true) "https:$pUrl" else pUrl
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
        val response = app.get(data, headers = mapOf("User-Agent" to clientUserAgent))
        val document = response.document

        // 1. Analisi Iframe e Embed
        document.select("iframe, div[data-src], div[data-litespeed-src], .dooplay_player_option").forEach { element ->
            var src = element.attr("src")
                .ifEmpty { element.attr("data-src") }
                .ifEmpty { element.attr("data-litespeed-src") }
                .ifEmpty { element.attr("data-url") }

            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("/")) src = mainUrl + src

            // Caso Embed Interno (quello che abbiamo visto nel log)
            if (src.contains("trembed")) {
                try {
                    val innerPage = app.get(src, referer = data).document
                    val innerIframe = innerPage.selectFirst("iframe")?.attr("src")
                    if (innerIframe != null) {
                        val finalUrl = if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe
                        processFinalUrl(finalUrl, src, callback)
                    }
                } catch (e: Exception) {
                    Log.e("GuardaPlay", "Errore embed: ${e.message}")
                }
            } else {
                processFinalUrl(src, data, callback)
            }
        }
        return true
    }

    private suspend fun processFinalUrl(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        Log.d("GuardaPlay", "Processo URL finale: $url")
        
        // Se è LoadM o Pancast, dobbiamo scavare per l'M3U8
        if (url.contains("loadm.cam") || url.contains("pancast.net")) {
            val res = app.get(url, referer = referer).text
            val m3u8Regex = Regex("""(https?.*?\.m3u8.*?)["']""")
            m3u8Regex.findAll(res).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink("GuardaPlay", "Server HD", videoUrl, ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        this.referer = url
                    }
                )
            }
        } else {
            // Altrimenti prova con gli estrattori standard (Voe, Vidhide, ecc.)
            loadExtractor(url, referer, { }, callback)
        }
    }
}
