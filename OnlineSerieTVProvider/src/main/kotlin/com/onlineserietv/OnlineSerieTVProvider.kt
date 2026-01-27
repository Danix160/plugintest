package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class OnlineSerieTVProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.online"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    // Usiamo una variabile fissa per gli header per evitare errori "unresolved reference"
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Cookie" to "cf_clearance=jHXaLK2g5rOsNeP_kjGZQiwDOQzqiepHZef0nIji0Fw-1769510252-1.2.1.1-MoXidTTq4G8XdXz7LlZ5dcGPdoUDK82XRtgNNzwPQpnsi4Q3sUtARXeQCosLhLnF620xZDdoVRfz3bje784KZWVYkHXDPF6ymurr_vVLPbFmVzC8jTatBj26OZPhbmepG_hDO3hTu0rO7AW2zFekaRAbiUev38UtPXZOFTdlpndrafxdnwbUrhaYDxjZIpynEwRk4utKNih3myFNc5cVaLhpSc_s_gVdVBmhIeKCudM"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/serie-tv-generi/animazione/" to "Animazione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val res = app.get(url, headers = commonHeaders)
        val document = res.document
        
        val items = document.select("div.items article.item, .item").mapNotNull { element ->
            val titleElement = element.selectFirst("h3 a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href") ?: ""
            
            val img = element.selectFirst(".poster img")
            val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } 
                         ?: img?.attr("src") 
                         ?: ""

            val isSeries = href.contains("/serie-tv/") || href.contains("/serietv/")

            newMovieSearchResponse(title, fixUrl(href), if (isSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        }
        
        return newHomePageResponse(name = request.name, list = items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query" 
        val res = app.get(url, headers = commonHeaders)
        val document = res.document
        
        return document.select("div.items article.item, div.result-item").mapNotNull { element ->
            val titleElement = element.selectFirst("h3 a, .title a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href") ?: ""
            
            val img = element.selectFirst(".poster img, img")
            val poster = img?.attr("data-src") ?: img?.attr("src") ?: ""
            
            val isSeries = href.contains("/serie-tv/") || href.contains("/serietv/")

            newMovieSearchResponse(title, fixUrl(href), if (isSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = commonHeaders)
        val document = res.document
        
        val title = document.selectFirst(".data h1")?.text()?.trim() ?: "Senza Titolo"
        val poster = document.selectFirst(".poster img")?.attr("src") ?: ""
        val plot = document.selectFirst(".wp-content p, .resumen")?.text()?.trim()
        
        val isSeries = url.contains("/serie-tv/") || document.selectFirst("#seasons") != null

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select(".episodios").forEach { season ->
                season.select("li").forEach { li ->
                    val a = li.selectFirst(".episodiotitle a") ?: return@forEach
                    val epHref = a.attr("href") ?: return@forEach
                    episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = a.text().trim()
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val res = app.get(data, headers = commonHeaders)
        val doc = res.document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("youtube")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }
        return true
    }
}
