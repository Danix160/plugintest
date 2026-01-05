package com.cb01

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CB01Provider : MainAPI() {
    override var mainUrl = "https://cb01net.baby"
    override var name = "CB01"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Film",
        "$mainUrl/serietv" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/page/$page/"
        }
        
        val document = app.get(url).document
        val home = document.select("div.card, div.post-item, article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // RICERCA UNIFICATA: Mostra Film e Serie TV insieme
    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        
        // 1. Cerca nei Film
        try {
            val filmResponse = app.get("$mainUrl/?s=$query").document
            filmResponse.select("div.card, div.post-item, article").forEach {
                it.toSearchResult()?.let { result -> allResults.add(result) }
            }
        } catch (e: Exception) { 
            // Log errore opzionale
        }

        // 2. Cerca nelle Serie TV
        try {
            val tvResponse = app.get("$mainUrl/serietv/?s=$query").document
            tvResponse.select("div.card, div.post-item, article").forEach {
                it.toSearchResult()?.let { result -> allResults.add(result) }
            }
        } catch (e: Exception) {
            // Log errore opzionale
        }

        // Ritorna la lista completa (Film + Serie)
        return allResults
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".card-title a, h2 a, .post-title a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Supporto per il Lazy Load delle immagini (data-src)
        val img = this.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: img?.attr("src")

        return if (href.contains("/serietv/") || title.contains("Serie TV", true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1.card-title")?.text()?.trim() ?: ""
        val posterElement = document.selectFirst(".card-image img, .poster img")
        val poster = posterElement?.attr("src")?.takeIf { it.isNotEmpty() } ?: posterElement?.attr("data-src")
        
        val plot = document.selectFirst(".entry-content p, .card-text")?.text()
        
        val isTvSeries = url.contains("/serietv/") || document.selectFirst("ul.episodi") != null

        return if (isTvSeries) {
            // CORREZIONE: Usa newEpisode invece del vecchio costruttore
            val episodes = document.select("ul.episodi li").mapNotNull {
                val link = it.select("a").attr("href")
                val epName = it.text().trim()
                if (link.isNullOrBlank()) return@mapNotNull null
                
                newEpisode(link) {
                    this.name = epName
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Cerca i link nei player (iframe) e nei bottoni download/spoiler
        document.select("iframe, a.btn-download, .sp-body a").forEach {
            val link = it.attr("src").ifEmpty { it.attr("href") }
            if (link.contains("http") && !link.contains("google")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
