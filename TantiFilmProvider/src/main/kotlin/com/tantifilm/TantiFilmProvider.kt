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
        "$mainUrl/serie-tv" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        
        val items = document.select("div.media-content, div.movie-item").mapNotNull { element ->
            val titleHeader = element.selectFirst("a.media-title, h2 a") ?: return@mapNotNull null
            val title = titleHeader.text().trim()
            val href = titleHeader.attr("href")
            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document

        return document.select("div.media-content, article").mapNotNull { element ->
            val titleHeader = element.selectFirst("a.media-title, h2 a") ?: return@mapNotNull null
            val title = titleHeader.text().trim()
            val href = titleHeader.attr("href")
            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.description, div.entry-content p")?.text()

        val episodes = mutableListOf<Episode>()
        
        // Determiniamo se è una Serie TV cercando i selettori delle stagioni/episodi
        val isSeries = document.select("div.season-list, div.episodes-list").isNotEmpty()
        
        if (isSeries) {
            // Logica per le Serie TV (da raffinare in base a come TantiFilm mostra gli episodi)
            document.select("div.season-item").forEachIndexed { sIndex, season ->
                season.select("a.episode-link").forEachIndexed { eIndex, ep ->
                    val href = ep.attr("href")
                    episodes.add(newEpisode(href) {
                        this.name = ep.text().trim()
                        this.season = sIndex + 1
                        this.episode = eIndex + 1
                    })
                }
            }
        } else {
            // Logica per i Film: unifichiamo i vari player (VOE, MixDrop, ecc.)
            val movieLinks = document.select("div.player-list a, ul.video-links li a").mapNotNull { 
                val href = it.attr("href")
                if(href.startsWith("http")) href else null
            }.joinToString("###")

            if (movieLinks.isNotEmpty()) {
                episodes.add(newEpisode(movieLinks) {
                    this.name = "Guarda il Film"
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
        data.split("###").forEach { url ->
            loadExtractor(url, subtitleCallback, callback)
        }
        return true
    }
}
