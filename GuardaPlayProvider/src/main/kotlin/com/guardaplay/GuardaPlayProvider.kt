package com.example // Cambia questo con il package del tuo plugin (es. com.guardaplay)

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.jsoup.nodes.Element

@CloudstreamPlugin
class GuardaPlayPlugin : Plugin() {
    override fun load(context: Context) {
        // Registra il provider principale
        registerMainAPI(GuardaPlayProvider())
    }
}

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.bar"
    override var name = "GuardaPlay"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Esplorazione della Home Page
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document.select("ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse("Ultimi Film", items)
    }

    // Ricerca basata sul file cerca.txt
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("ul.post-lst li.movies").mapNotNull {
            it.toSearchResult()
        }
    }

    // Caricamento dei dettagli del film
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        
        // Estrazione metadati
        val poster = document.selectFirst("div.post-poster img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p")?.text()
        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // ESTRAZIONE LINK VIDEO (Punto critico risolto)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Cerca negli Iframe (inclusi quelli con lazy load di LiteSpeed)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() }
                ?: iframe.attr("data-src").takeIf { it.isNotBlank() }
                ?: iframe.attr("data-litespeed-src")
            
            if (!src.isNullOrBlank()) {
                val finalUrl = if (src.startsWith("/")) mainUrl + src else src
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        }

        // 2. Cerca nei bottoni dei server (comune nei temi Dooplay/ToroThemes)
        document.select("ul.idTabs li, .dooplay_player_option, .server-item").forEach { server ->
            val embedCode = server.attr("data-url").takeIf { it.isNotBlank() }
                ?: server.attr("data-embed")
            
            if (!embedCode.isNullOrBlank()) {
                // Se il link è codificato in Base64 (comune in alcuni siti), andrebbe decodificato qui
                val finalUrl = if (embedCode.startsWith("/")) mainUrl + embedCode else embedCode
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // Funzione di utilità per mappare gli elementi HTML
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title")?.text() ?: return null
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("//")) "https:$it" else it 
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}
