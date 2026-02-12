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
        // Selettori per gli elementi della home
        val items = doc.select(".promo-item, .movie-item, .m-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("In Evidenza", items)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Questa funzione viene chiamata per la prima pagina (page 1)
        return search(query, 1)
    }

    // Funzione di ricerca con supporto alla paginazione per risultati infiniti
    override suspend fun search(query: String, page: Int): List<SearchResponse> {
        // DLE (il motore di Cineblog) usa search_start per la paginazione
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query&search_start=$page"
        val doc = app.get(url).document
        
        // Estraiamo i risultati dalla pagina corrente
        return doc.select(".m-item, .movie-item, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        
        // Filtriamo i link che non sono contenuti (tag, categorie, ecc.)
        if (href.contains("/tags/") || href.contains("/category/")) return null

        // Titolo: cerchiamo in h2, h3 o attributo title
        val title = this.selectFirst("h2, h3, .m-title")?.text() 
            ?: a.attr("title").ifEmpty { a.text() }
        
        if (title.isNullOrBlank()) return null

        // Poster: gestiamo caricamento normale e lazy-loading (data-src)
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src") ?: img?.attr("src")
        )

        val isTv = href.contains("/serie-tv/") || title.contains("serie tv", true)

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst(".film-poster img, .m-img img")?.attr("src"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        
        // Verifica se è una serie TV tramite URL o presenza di tab episodi
        val isSerie = url.contains("/serie-tv/") || doc.select("#tv_tabs").isNotEmpty()

        return if (isSerie) {
            val episodes = doc.select(".tt_series li, .episodes-list li").mapNotNull { li ->
                val link = li.selectFirst("a")
                if (link != null) {
                    val epData = link.attr("data-link").ifEmpty { link.attr("href") }
                    newEpisode(epData) { this.name = link.text() }
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
        // Se i dati sono già un link esterno (comune nelle serie)
        if (data.startsWith("http") && !data.contains(mainUrl)) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

        // Se è la pagina del film, cerchiamo i link ai vari hoster
        val doc = app.get(data).document
        doc.select("a[href*='mixdrop'], a[href*='supervideo'], a[href*='vidoza']")
            .forEach { 
                val link = it.attr("href")
                // Gestione del redirect intermedio /vai/
                val finalUrl = if (link.contains("/vai/")) {
                    try { app.get(link).url } catch (e: Exception) { link }
                } else link
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        return true
    }
}
