package com.tantifilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

class TantiFilmProvider : MainAPI() {
    override var mainUrl = "https://tanti-film.stream"
    override var name = "TantiFilm"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        
        // TantiFilm usa 'div#archive-content article' per le liste film/serie
        val items = document.select("div#archive-content article, article.item").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            
            // Filtro per evitare di prendere link ai tag o categorie
            if (!href.contains("/film/") && !href.contains("/serie-tv/")) return@mapNotNull null
            if (href.endsWith("/film/") || href.endsWith("/serie-tv/")) return@mapNotNull null

            val title = element.selectFirst("h3, h2, .title")?.text()?.trim() 
                ?: element.selectFirst("img")?.attr("alt")?.trim()
                ?: "No Title"

            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document

        return document.select("div.result-item, article").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".title, h3")?.text() ?: "Cerca..."
            val href = fixUrl(link.attr("href"))
            val img = element.selectFirst("img")
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = img?.attr("src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".poster img, .image img")?.attr("src")
        val plot = document.selectFirst(".description p, #sinopsis p")?.text()

        val episodes = mutableListOf<Episode>()
        val isSeries = url.contains("/serie-tv/") || document.select("#seasons").isNotEmpty()

        if (isSeries) {
            document.select(".season").forEach { season ->
                val sNum = season.selectFirst(".title")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
                season.select(".episodios li").forEach { ep ->
                    val a = ep.selectFirst("a") ?: return@forEach
                    episodes.add(newEpisode(fixUrl(a.attr("href"))) {
                        this.name = a.text()
                        this.season = sNum
                        this.episode = ep.selectFirst(".numerando")?.text()?.substringAfter("-")?.trim()?.toIntOrNull()
                    })
                }
            }
        } else {
            val sources = mutableListOf<String>()
            document.select("ul#playeroptionsul li").forEach { 
                val dataUrl = it.attr("data-url")
                if (!dataUrl.isNullOrBlank()) sources.add(dataUrl)
            }
            
            if (sources.isNotEmpty()) {
                episodes.add(newEpisode(sources.joinToString("###")) {
                    this.name = "Film"
                })
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
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
        data.split("###").forEach { 
            if (it.startsWith("http")) loadExtractor(it, subtitleCallback, callback)
        }
        return true
    }
}
