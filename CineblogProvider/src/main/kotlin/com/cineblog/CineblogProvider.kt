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
        val items = doc.select("article, .m-item, .movie-item, .promo-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("Novità", items)), false)
    }

    // Usiamo la logica GET che hai confermato funzionare, aggiungendo il supporto alle pagine
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // Costruiamo l'URL includendo il parametro per la pagina (search_start)
        // Pagina 1 -> search_start=1, Pagina 2 -> search_start=2, ecc.
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query&search_start=$page"
        
        val doc = app.get(url).document
        
        // Usiamo i selettori che hai indicato come funzionanti
        val results = doc.select(".m-item, .movie-item, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        // Il cast 'as SearchResponseList?' serve a evitare l'errore di compilazione Gradle
        return results as SearchResponseList?
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Cerchiamo il link (a) e il titolo
        val a = this.selectFirst("h2 a, h3 a, .m-title a, a") ?: return null
        val href = fixUrl(a.attr("href"))
        
        // Escludiamo link non pertinenti
        if (href.contains("/tags/") || href.contains("/category/") || href == "$mainUrl/") return null

        val title = a.text().trim().ifEmpty { 
            this.selectFirst("img")?.attr("alt") 
        } ?: return null

        // Gestione locandina
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))

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
        val title = doc.selectFirst("h1, .story-heading, .m-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst(".story-cover img, .film-poster img, .m-img img")?.attr("src"))
        val plot = doc.selectFirst(".story, .m-desc, #news-id")?.text() ?: doc.selectFirst("meta[name=description]")?.attr("content")
        
        val isSerie = url.contains("/serie-tv/") || doc.select("#tv_tabs, .episodes-list").isNotEmpty()

        return if (isSerie) {
            val episodes = doc.select(".tt_series li, .episodes-list li, .story a[href*='episodio']").mapNotNull { li ->
                val link = if (li.tagName() == "a") li else li.selectFirst("a")
                if (link != null) {
                    val epData = link.attr("href")
                    newEpisode(epData) { this.name = link.text().trim() }
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
        val doc = app.get(data).document
        doc.select("a[href*='mixdrop'], a[href*='supervideo'], a[href*='vidoza'], a[href*='streamtape'], a[href*='filemoon']")
            .forEach { 
                val link = it.attr("href")
                val finalUrl = if (link.contains("/vai/")) {
                    try { app.get(link).url } catch (e: Exception) { link }
                } else link
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        return true
    }
}
