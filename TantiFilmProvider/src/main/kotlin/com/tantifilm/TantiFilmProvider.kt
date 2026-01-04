package com.tantifilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup

class TantiFilmProvider : MainAPI() {
    override var mainUrl = "http://tanti-film.stream"
    override var name = "TantiFilm"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        
        // Selettori mirati per la struttura "DooPlay" di TantiFilm
        val items = document.select("div.items article, div.quad_blog, div.result-item").mapNotNull { element ->
            val link = element.selectFirst("div.poster a, div.image a, h3 a") ?: return@mapNotNull null
            val title = element.selectFirst("div.data h3 a, h2 a, h3 a")?.text()?.trim() 
                ?: element.selectFirst("img")?.attr("alt")?.trim() 
                ?: "Senza Titolo"
            
            val href = fixUrl(link.attr("href"))
            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("data-lazy-src") ?: img?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document

        return document.select("div.result-item, article.item").mapNotNull { element ->
            val link = element.selectFirst("div.image a, div.poster a") ?: return@mapNotNull null
            val title = element.selectFirst("div.details div.title a")?.text()?.trim() 
                ?: element.selectFirst("img")?.attr("alt")?.trim() 
                ?: "Senza Titolo"
            
            val href = fixUrl(link.attr("href"))
            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.poster img")?.attr("src") 
            ?: document.selectFirst("div.image img")?.attr("src")
        val plot = document.selectFirst("div.description, div.wp-content p, #sinopsis")?.text()

        val episodes = mutableListOf<Episode>()
        
        // Riconoscimento Serie TV: verifica presenza stagioni
        val seasons = document.select("div.seasons")
        val isSeries = seasons.isNotEmpty()

        if (isSeries) {
            seasons.select("div.season").forEachIndexed { sIndex, season ->
                season.select("ul.episodios li").forEachIndexed { eIndex, ep ->
                    val epLink = ep.selectFirst("a") ?: return@forEachIndexed
                    val epHref = fixUrl(epLink.attr("href"))
                    val epName = ep.selectFirst("div.episodiotitle a")?.text() ?: "Episodio ${eIndex + 1}"
                    
                    episodes.add(newEpisode(epHref) {
                        this.name = epName
                        this.season = sIndex + 1
                        this.episode = eIndex + 1
                    })
                }
            }
        } else {
            // Logica Film: TantiFilm usa spesso dei tab o iframe per i player video
            val movieLinks = mutableListOf<String>()
            
            // 1. Cerca link diretti nei tab (molto comune su questo tema)
            document.select("ul.idTabs li a, div.source-box a").forEach {
                val href = it.attr("href")
                if (href.startsWith("http")) movieLinks.add(href)
            }
            
            // 2. Cerca iframe (se non ci sono link diretti)
            document.select("div.video-player iframe, div.player iframe").forEach {
                val src = it.attr("src")
                if (src.isNotBlank()) movieLinks.add(fixUrl(src))
            }

            if (movieLinks.isNotEmpty()) {
                episodes.add(newEpisode(movieLinks.distinct().joinToString("###")) {
                    this.name = "Riproduci Film"
                })
            }
        }

        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, tvType, episodes.firstOrNull()?.data ?: "") {
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
        // Supporto per link unificati separati da ###
        data.split("###").forEach { url ->
            val cleanUrl = url.trim()
            if (cleanUrl.contains("http")) {
                loadExtractor(cleanUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
