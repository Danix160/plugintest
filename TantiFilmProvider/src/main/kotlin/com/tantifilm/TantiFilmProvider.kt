package com.tantifilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class TantiFilmProvider : MainAPI() {
    // Definizione delle informazioni di base del sito 
    override var mainUrl = "https://tanti-film.stream"
    override var name = "TantiFilm"
    override val hasMainPage = true
    override var lang = "it"
    override val hasQuickSearch = true

    // Definizione delle sezioni della home page
    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film HD",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    // Analisi della pagina principale per estrarre i film
    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse {
        val document = app.get(request.data).document
        // Utilizzo del selettore tipico di DLE per gli elementi (basato su analisi del codice)
        val home = document.select("div.mov").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Funzione di ricerca
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=$query"
        val document = app.get(url).document

        return document.select("div.mov").mapNotNull {
            it.toSearchResult()
        }
    }

    // Caricamento dei dettagli del film e dei link video
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: ""
        val poster = document.selectFirst("div.short-img img")?.attr("src")
        val description = document.selectFirst("div.full-text")?.text()

        // Estrazione dei link degli host video (MixDrop, Vidoza, etc.)
        val links = mutableListOf<ExtractorLink>()
        document.select("iframe").forEach { iframe ->
            val source = iframe.attr("src")
            if (source.isNotEmpty()) {
                loadExtractor(source, url, links)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // Helper per convertire un elemento HTML in un risultato di ricerca
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a.short-title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}
