package com.cb01

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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

    // RICERCA DOPPIA: Film + Serie TV
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        // Eseguiamo le due ricerche in parallelo per essere più veloci
        val movieSearch = async { 
            try {
                app.get("$mainUrl/?s=$query").document.select("div.card, div.post-item, article").mapNotNull { it.toSearchResult() }
            } catch (e: Exception) { emptyList<SearchResponse>() }
        }
        
        val tvSearch = async { 
            try {
                app.get("$mainUrl/serietv/?s=$query").document.select("div.card, div.post-item, article").mapNotNull { it.toSearchResult() }
            } catch (e: Exception) { emptyList<SearchResponse>() }
        }

        // Uniamo i risultati di entrambe le ricerche
        return@coroutineScope movieSearch.await() + tvSearch.await()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".card-title a, h2 a, .post-title a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Gestione Lazy Load (data-src) presente nel codice inviato
        val posterUrl = this.selectFirst("img")?.let { 
            val dataSrc = it.attr("data-src")
            if (dataSrc.isNotEmpty()) dataSrc else it.attr("src")
        }

        // Rilevamento automatico se è una serie o un film dal link
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
        val poster = document.selectFirst(".card-image img, .poster img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }
        val plot = document.selectFirst(".entry-content p, .card-text")?.text()
        
        val isTvSeries = url.contains("/serietv/") || document.selectFirst("ul.episodi") != null

        return if (isTvSeries) {
            val episodes = document.select("ul.episodi li").mapNotNull {
                val link = it.select("a").attr("href")
                val name = it.text().trim()
                if (link.isNullOrBlank()) return@mapNotNull null
                
                // USA newEpisode per evitare errori di compilazione
                newEpisode(link) {
                    this.name = name
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
        // Estrazione link dai player e dagli spoiler (classe .sp-body tipica di CB01)
        document.select("iframe, a.btn-download, .sp-body a").forEach {
            val link = it.attr("src").ifEmpty { it.attr("href") }
            if (link.contains("http") && !link.contains("google")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
