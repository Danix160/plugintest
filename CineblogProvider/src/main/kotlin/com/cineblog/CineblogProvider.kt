package com.cineblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.club"
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(mainUrl).document
        val items = doc.select(".promo-item, .movie-item, .m-item, #dle-content > div").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("In Evidenza", items)), false)
    }

    // Funzione di conversione Element -> SearchResponse migliorata con i tuoi selettori
    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".movie-title a, .m-title a, h2 a, h3 a") ?: return null
            
        val title = titleElement.text().trim()
        if (title.isBlank()) return null
        
        val href = fixUrl(titleElement.attr("href"))
        if (href.contains("/tags/") || href.contains("/category/")) return null
        
        // Poster: gestisce sia data-src (lazy) che src normale
        val imgElement = this.selectFirst("img")
        val poster = imgElement?.attr("data-src") ?: imgElement?.attr("src")
        
        // Voto e Episodi (per le serie)
        val rating = this.selectFirst(".label.rate.small, .imdb-rate")?.text()
        val isSeries = this.selectFirst(".label.episode") != null || href.contains("/serie-tv/")
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(poster)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
                // Aggiunta opzionale dello score se presente
                if (rating != null) this.score = Score.from(rating, 10)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val doc = app.get(searchUrl).document
        
        // Usa i selettori specifici per la griglia di ricerca
        return doc.select("#dle-content > div, .movie-item, .m-item, article").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        
        // Recupero poster specifico dal player (come visto nel file film.txt)
        val poster = fixUrlNull(
            doc.selectFirst("img._player-cover")?.attr("src") 
            ?: doc.selectFirst(".story-poster img, .m-img img, img[itemprop='image']")?.attr("src")
        )
        
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")
        val isSerie = url.contains("/serie-tv/") || doc.select("#tv_tabs, .tt_series").isNotEmpty()

        return if (isSerie) {
            val episodes = doc.select(".tt_series li, .episodes-list li").mapNotNull { li ->
                val link = li.selectFirst("a")
                if (link != null) {
                    val epData = link.attr("data-link").ifEmpty { link.attr("href") }
                    newEpisode(epData) { 
                        this.name = link.text().trim() 
                    }
                } else null
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
        // Se il dato passato è un link diretto ad un extractor
        if (data.startsWith("http") && !data.contains(mainUrl)) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

        val doc = app.get(data).document
        
        // Estrattore specifico per Film basato sul tuo HTML (data-link)
        // Cerca in tutti i mirror visibili e nascosti
        doc.select("ul._player-mirrors li, div._hidden-mirrors li").forEach { li ->
            val link = li.attr("data-link")
            if (link.isNotBlank() && !link.contains("mostraguarda.stream")) {
                loadExtractor(fixUrl(link), data, subtitleCallback, callback)
            }
        }
        
        // Supporto legacy/Serie TV per link classici a[href]
        doc.select("a[href*='mixdrop'], a[href*='supervideo'], a[href*='vidoza']").forEach { 
            loadExtractor(it.attr("href"), data, subtitleCallback, callback)
        }

        return true
    }
}
