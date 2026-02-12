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

    // Simuliamo un browser reale per evitare il blocco del sito
    private val header = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(mainUrl, headers = header).document
        
        // Dai tuoi file: la home usa div.promo-item
        val items = doc.select("div.promo-item, div.movie-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("In Evidenza", items)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Se la ricerca interna ?s= non va, proviamo quella più specifica del motore DLE
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val doc = app.get(url, headers = header).document
        
        // Nei risultati di ricerca i contenitori cambiano spesso in .m-item
        return doc.select("div.m-item, div.movie-item, div.promo-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        
        // Estraiamo il titolo dall'attributo 'title' o dal tag h3/h2
        val title = this.selectFirst("h2, h3, .m-title")?.text() ?: a.attr("title").ifEmpty { a.text() }
        if (title.isNullOrBlank()) return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))

        // Se l'URL o il titolo contengono indizi sulla serie
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
        val doc = app.get(url, headers = header).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst("div.film-poster img, .m-img img")?.attr("src"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        
        // Identifichiamo se è una serie dai tab degli episodi (ID tv_tabs nei tuoi file)
        val isSerie = url.contains("/serie-tv/") || doc.select("#tv_tabs, .tt_series").isNotEmpty()

        return if (isSerie) {
            val episodes = doc.select("div.tt_series li, .episodes-list li").mapNotNull { li ->
                val link = li.selectFirst("a") ?: return@mapNotNull null
                val epData = link.attr("data-link").ifEmpty { link.attr("href") }
                if (epData.isBlank()) return@mapNotNull null
                
                newEpisode(epData) {
                    this.name = link.text().trim()
                }
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
        // Se il dato è già un link hoster
        if (data.startsWith("http") && !data.contains(mainUrl)) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

        val doc = app.get(data, headers = header).document
        // Cerchiamo i link diretti agli hoster nella pagina
        doc.select("a[href*='mixdrop'], a[href*='supervideo'], a[href*='vidoza']")
            .forEach { 
                val link = it.attr("href")
                // Se c'è un redirect intermedio "/vai/"
                val finalUrl = if (link.contains("/vai/")) {
                    try { app.get(link, headers = header).url } catch (e: Exception) { link }
                } else link
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        return true
    }
}
