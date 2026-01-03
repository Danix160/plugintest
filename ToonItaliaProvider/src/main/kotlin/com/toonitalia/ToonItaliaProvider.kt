package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.SubtitleFile

class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "Toon Italia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val title = titleHeader.text()
            val href = titleHeader.attr("href")
            val posterUrl = article.selectFirst("img")?.attr("src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.entry-content img")?.attr("src")
        
        val plot = document.select("h3:contains(Trama) + p").text().ifEmpty {
            document.select("div.entry-content p").firstOrNull { it.text().length > 30 }?.text()
        }

        val episodes = mutableListOf<Episode>()
        
        // Estrazione pulsanti maxbutton
        document.select("a[class*='maxbutton']").forEach { button ->
            val link = button.attr("href")
            if (link.startsWith("http") && !link.contains("share")) {
                episodes.add(newEpisode(link) { 
                    this.name = button.text().trim() 
                })
            }
        }

        // Estrazione link testuali
        document.select("div.entry-content a").forEach { a ->
            val href = a.attr("href")
            val text = a.text().trim()
            val isVideoHost = listOf("voe", "vidhide", "chuckle-tube", "luluvdo", "mixdrop", "streamtape").any { 
                href.contains(it) || text.contains(it, ignoreCase = true) 
            }
            
            if (isVideoHost) {
                episodes.add(newEpisode(href) {
                    this.name = if (text.length < 2) "Streaming" else text
                })
            }
        }

        return if (episodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        val cleanUrl = data.trim()
        if (!cleanUrl.startsWith("http")) return false

        val finalUrl = if (cleanUrl.contains("chuckle-tube.com") || cleanUrl.contains("luluvdo.com")) {
            try {
                // Eseguiamo la richiesta per seguire il redirect al vero host
                val response = app.get(
                    cleanUrl, 
                    allowRedirects = true, 
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                )
                response.url 
            } catch (e: Exception) {
                cleanUrl 
            }
        } else {
            cleanUrl
        }

        // loadExtractor si occupa di identificare il player corretto
        loadExtractor(finalUrl, "https://toonitalia.xyz/", subtitleCallback, callback)

        return true
    }
}
