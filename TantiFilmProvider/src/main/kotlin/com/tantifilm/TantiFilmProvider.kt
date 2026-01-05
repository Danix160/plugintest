package com.tantifilm

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
        
        val items = document.select("div.movie-item").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (href.isNullOrBlank()) return@mapNotNull null
            
            val title = element.selectFirst(".m-title")?.text()?.trim() 
                ?: a.attr("title")?.trim() 
                ?: "Film"
                
            val poster = element.selectFirst("img")?.attr("src")
            val quality = element.selectFirst(".m-quality")?.text()

            // Usiamo fixUrl(href!!) solo dopo aver verificato che non è null o blank
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                if (!poster.isNullOrBlank()) {
                    this.posterUrl = fixUrl(poster)
                }
                addQuality(quality)
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val document = app.get(url).document
        
        return document.select("div.movie-item").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (href.isNullOrBlank()) return@mapNotNull null
            
            val title = (element.selectFirst(".m-title")?.text() ?: a.text()).trim()
            val poster = element.selectFirst("img")?.attr("src")
            
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                if (!poster.isNullOrBlank()) {
                    this.posterUrl = fixUrl(poster)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Senza Titolo"
        val poster = document.selectFirst(".m-img img")?.attr("src")
        val plot = document.selectFirst(".m-desc")?.text()?.trim()
        
        val isSeries = url.contains("/serie-tv/")
        val episodes = mutableListOf<Episode>()

        if (isSeries) {
            document.select(".s-eps a").forEach { ep ->
                val epHref = ep.attr("href")
                if (!epHref.isNullOrBlank()) {
                    episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = ep.text().trim()
                    })
                }
            }
        } else {
            val playerUrl = document.selectFirst("iframe")?.attr("src")
            if (!playerUrl.isNullOrBlank()) {
                episodes.add(newEpisode(playerUrl) { this.name = "Film" })
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                if (!poster.isNullOrBlank()) this.posterUrl = fixUrl(poster)
                this.plot = plot
            }
        } else {
            val data = episodes.firstOrNull()?.data ?: ""
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                if (!poster.isNullOrBlank()) this.posterUrl = fixUrl(poster)
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
