package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CB01Provider : MainAPI() {
    override var mainUrl = "https://cb01net.baby"
    override var name = "CB01"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Configurazione della Home Page
    override val mainPage = mainPageOf(
         mainUrl to "Film",
        "$mainUrl/serietv" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // WordPress usa il formato /page/X/
        val url = request.data + page
        val document = app.get(url).document
        
        // Selettore basato sulla classe .card tipica del tema Sequex/CB01
        val home = document.select("div.card, div.post-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("div.card, div.post-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Funzione di utilità per estrarre i dati dalla card
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".card-title a, h2 a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src") 
            ?: this.selectFirst("img")?.attr("src")

        return if (title.contains("Serie TV", ignoreCase = true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".poster img")?.attr("src")
        val plot = document.selectFirst(".entry-content p")?.text()
        
        // Verifica se è una serie TV o un film
        val isTvSeries = url.contains("/serietv/") || document.selectFirst("ul.episodi") != null

        return if (isTvSeries) {
            // Logica per Serie TV (estrazione stagioni/episodi)
            val episodes = document.select("ul.episodi li").map { 
                // Qui andrebbe la logica per mappare gli episodi
                Episode(it.select("a").attr("href"), it.text())
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val document = app.get(data).document
        
        // CB01 mette i link in iframe o bottoni dopo la descrizione
        document.select("iframe, a.btn-download").forEach {
            val link = it.attr("src").ifEmpty { it.attr("href") }
            if (link.contains("http")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
