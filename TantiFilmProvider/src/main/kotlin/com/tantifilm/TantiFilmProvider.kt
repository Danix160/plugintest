package com.tantifilm

import android.util.Log // Importante per i log
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
        
        // --- LOG DI DEBUG ---
        Log.d("TantiFilm", "HTML Length = ${document.html().length}")
        Log.d("TantiFilm", "Articles Found = ${document.select("article").size}")
        // --------------------

        val items = document.select("article").mapNotNull { element ->
            try {
                val a = element.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))
                
                // Salta i link che non sono film (es. categorie)
                if (href.removeSuffix("/").endsWith("/film") || href.removeSuffix("/").endsWith("/serie-tv")) return@mapNotNull null

                val title = element.selectFirst("h3, h2, .title")?.text()?.trim() 
                    ?: a.attr("title") 
                    ?: "Video"

                val posterUrl = element.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }

                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } catch (e: Exception) { null }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.result-item, article").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".title a, h3")?.text() ?: a.text()
            
            newMovieSearchResponse(title, fixUrl(a.attr("href")), TvType.Movie) {
                this.posterUrl = element.selectFirst("img")?.let { it.attr("src") }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val isSeries = url.contains("/serie-tv/")
        
        val episodes = mutableListOf<Episode>()
        if (isSeries) {
            document.select("ul.episodios li").forEach { ep ->
                val a = ep.selectFirst("a") ?: return@forEach
                episodes.add(newEpisode(fixUrl(a.attr("href"))) {
                    this.name = a.text()
                })
            }
        } else {
            // Cerca il link del player
            val link = document.selectFirst("iframe")?.attr("src") 
                ?: document.selectFirst("ul#playeroptionsul li")?.attr("data-url")
            
            if (link != null) episodes.add(newEpisode(link) { this.name = "Film" })
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes)
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "")
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.startsWith("http")) {
            loadExtractor(data, subtitleCallback, callback)
        }
        return true
    }
}
