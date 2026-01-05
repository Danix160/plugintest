package com.tantifilm

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class TantiFilmProvider : MainAPI() {
    override var mainUrl = "https://tanti-film.stream"
    override var name = "TantiFilm"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        // Selettore specifico per il template DataLife Engine di TantiFilm
        val items = document.select("div.movie-item").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val title = element.selectFirst(".m-title")?.text()?.trim() ?: a.attr("title")
            val poster = element.selectFirst("img")?.attr("src")
            val quality = element.selectFirst(".m-quality")?.text()

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrl(poster ?: "")
                addQuality(quality)
            }
        }
        
        Log.d("TantiFilm", "Elementi trovati: ${items.size}")
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val document = app.get(url).document
        
        return document.select("div.movie-item").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".m-title")?.text() ?: a.text()
            
            newMovieSearchResponse(title, fixUrl(a.attr("href")), TvType.Movie) {
                this.posterUrl = fixUrl(element.selectFirst("img")?.attr("src") ?: "")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".m-img img")?.attr("src")
        val plot = document.selectFirst(".m-desc")?.text()
        
        val isSeries = url.contains("/serie-tv/")
        val episodes = mutableListOf<Episode>()

        if (isSeries) {
            // Estrazione episodi per serie TV
            document.select(".s-eps a").forEach { ep ->
                episodes.add(newEpisode(fixUrl(ep.attr("href"))) {
                    this.name = ep.text().trim()
                })
            }
        } else {
            // Per i film cerchiamo il player nell'area dedicata
            val playerUrl = document.selectFirst("iframe")?.attr("src")
            if (playerUrl != null) {
                episodes.add(newEpisode(playerUrl) { this.name = "Film" })
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = fixUrl(poster ?: "")
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
        if (data.startsWith("http")) {
            loadExtractor(data, subtitleCallback, callback)
        }
        return true
    }
}
