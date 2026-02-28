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
        
        // Fix protocollo poster
        val posterUrl = selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("//")) "https:$it" else it 
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val document = response.document
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
        Log.d("GuardaPlay", "Ricerca link per: $data")
        
        val response = app.get(data, headers = mapOf("User-Agent" to clientUserAgent))
        val html = response.text
        val document = response.document

        // 1. Ricerca link M3U8 diretti (Regex globale)
        val m3u8Regex = Regex("""https?[:\\]+[/\\\w\d\.-]+\.m3u8(?:\?v=[\d]+)?""")
        m3u8Regex.findAll(html).forEach { match ->
            val url = match.value.replace("\\/", "/")
            Log.d("GuardaPlay", "M3U8 diretto: $url")
            callback.invoke(
                newExtractorLink("GuardaPlay", "HD Player", url, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.P1080.value
                    this.referer = "https://loadm.cam/"
                }
            )
        }

        // 2. Analisi Elementi Iframe e Lazy-Load (LiteSpeed)
        document.select("iframe, div[data-src], div[data-litespeed-src]").forEach { element ->
            var src = element.attr("src")
                .ifEmpty { element.attr("data-src") }
                .ifEmpty { element.attr("data-litespeed-src") }
            
            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"

            Log.d("GuardaPlay", "Sorgente trovata: $src")

            // Se è un hoster esterno noto
            if (src.contains(Regex("vidhide|voe|streamwish|mixdrop|filemoon|vood|dood|streamtape"))) {
                loadExtractor(src, data, subtitleCallback, callback)
            } 
            // Se è il player proprietario (LoadM / Pancast)
            else if (src.contains("loadm.cam") || src.contains("pancast.net")) {
                try {
                    val frameHtml = app.get(src, referer = data).text
                    m3u8Regex.find(frameHtml)?.value?.let { directUrl ->
                        callback.invoke(
                            newExtractorLink("GuardaPlay", "HD Player", directUrl.replace("\\/", "/"), ExtractorLinkType.M3U8) {
                                this.quality = Qualities.P1080.value
                                this.referer = src
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("GuardaPlay", "Errore estrazione frame: ${e.message}")
                }
            }
        }

        return true
    }
}
