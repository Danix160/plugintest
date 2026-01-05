package com.tantifilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class TantiFilmProvider : MainAPI() {
    override var mainUrl = "https://tanti-film.stream"
    override var name = "TantiFilm"
    override val hasMainPage = true
    override var lang = "it"
    override val hasQuickSearch = true

    // Headers senza override
    val pluginHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film HD",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = pluginHeaders).document
        val home = document.select("div.mov").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val document = app.get(url, headers = pluginHeaders).document

        return document.select("div.mov").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = pluginHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Nessun Titolo"
        val poster = document.selectFirst("div.short-img img")?.attr("abs:src")
        val description = document.selectFirst("div.full-text")?.text()?.trim()
        
        val isSerie = url.contains("/serie-tv")

        return if (isSerie) {
            // SOLUZIONE: Usiamo newEpisode() invece del costruttore diretto per evitare il warning/error di deprecation
            val episodes = listOf(
                newEpisode(url) {
                    this.name = "Guarda Episodio"
                }
            )
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = pluginHeaders).document
        
        document.select("iframe").forEach { iframe ->
            val source = iframe.attr("abs:src")
            if (source.isNotEmpty() && !source.contains("google") && !source.contains("facebook")) {
                loadExtractor(source, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("a.short-title") ?: return null
        val title = titleElement.text().trim()
        val href = titleElement.attr("abs:href")
        val posterUrl = this.selectFirst("img")?.attr("abs:src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}
