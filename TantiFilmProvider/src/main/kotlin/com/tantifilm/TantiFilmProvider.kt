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

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film HD",
        "$mainUrl/serie/" to "Serie TV"
    )

    // CORREZIONE 1: La firma corretta usa MainPageRequest (non HomePageRequest)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div.mov").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=$query"
        val document = app.get(url).document

        return document.select("div.mov").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: ""
        val poster = document.selectFirst("div.short-img img")?.attr("src")
        val description = document.selectFirst("div.full-text")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // CORREZIONE 2: Caricamento link video con callback corretta per l'extractor
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("iframe").forEach { iframe ->
            val source = iframe.attr("src")
            if (source.isNotEmpty()) {
                // Carica l'extractor passando le callback per sottotitoli e link video
                loadExtractor(source, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a.short-title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}
