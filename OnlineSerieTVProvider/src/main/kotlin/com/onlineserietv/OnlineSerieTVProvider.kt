package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class OnlineSerieTVProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.com"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    // Header per simulare Googlebot e bypassare i controlli Cloudflare
    private val botHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film-streaming-ita-gratis/" to "Film",
        "$mainUrl/serie-tv-streaming-ita/" to "Serie TV",
        "$mainUrl/generi/animazione/" to "Animazione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        // Applichiamo i botHeaders qui
        val res = app.get(url, headers = botHeaders)
        val document = res.document
        
        val items = document.select(".items .item").mapNotNull { element ->
            val titleElement = element.selectFirst(".data h3 a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href") ?: ""
            
            val img = element.selectFirst(".poster img")
            val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } 
                         ?: img?.attr("src") 
                         ?: ""

            val isSeries = href.contains("/serie-tv/") || element.selectFirst(".tvshow") != null

            newMovieSearchResponse(title, fixUrl(href), if (isSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val res = app.get(url, headers = botHeaders)
        val document = res.document
        
        return document.select(".result-item").mapNotNull { element ->
            val titleElement = element.selectFirst(".title a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href") ?: ""
            val poster = element.selectFirst("img")?.attr("src") ?: ""
            
            val typeText = element.selectFirst(".type span")?.text() ?: ""
            val isSeries = typeText.contains("Serie", ignoreCase = true)

            newMovieSearchResponse(title, fixUrl(href), if (isSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = botHeaders)
        val document = res.document
        
        val title = document.selectFirst(".data h1")?.text()?.trim() 
                    ?: document.selectFirst("h1")?.text()?.trim() 
                    ?: "Senza Titolo"
        
        val poster = document.selectFirst(".poster img")?.attr("src") ?: ""
        val plot = document.selectFirst(".wp-content p, .resumen")?.text()?.trim()
        
        val isSeries = url.contains("/serie-tv/") || document.selectFirst("#seasons") != null

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select(".episodios").forEach { season ->
                season.select("li").forEach { li ->
                    val a = li.selectFirst(".episodiotitle a") ?: return@forEach
                    val epHref = a.attr("href")
                    val epName = a.text().trim()
                    
                    episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = epName
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
            }
        } else {
            val movieData = document.selectFirst("iframe")?.attr("src") ?: ""
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = fixUrl(poster)
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
            loadExtractor(data, subtitleCallback, callback, headers = botHeaders)
        }
        return true
    }
}
