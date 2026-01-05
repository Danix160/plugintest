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

    // Importante: User-Agent reale per evitare blocchi "UnknownHost/Protocol"
    override val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film" to "Film HD",
        "$mainUrl/serie-tv" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Gestione corretta della paginazione DLE
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        
        // Usiamo safeApiCall per evitare crash se il DNS fallisce
        val document = app.get(url, headers = headers).document
        val home = document.select("div.mov").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val document = app.get(url, headers = headers).document

        return document.select("div.mov").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.short-img img")?.attr("abs:src")
        val description = document.selectFirst("div.full-text")?.text()?.trim()
        
        // Verifica se è una serie TV per mostrare i tasti degli episodi
        val isSerie = url.contains("/serie-tv") || document.selectFirst("div.video-series") != null

        return if (isSerie) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, url) {
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
        val document = app.get(data, headers = headers).document
        
        // Cerca tutti gli iframe dei player
        document.select("iframe").forEach { iframe ->
            val source = iframe.attr("abs:src")
            if (source.isNotEmpty() && !source.contains("google") && !source.contains("facebook")) {
                loadExtractor(source, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Selettori specifici per il template di TantiFilm
        val titleElement = this.selectFirst("a.short-title") ?: return null
        val title = titleElement.text().trim()
        val href = titleElement.attr("abs:href")
        val posterUrl = this.selectFirst("img")?.attr("abs:src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}
