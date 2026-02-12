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
        // Aggiungiamo 'article' ai selettori per essere sicuri
        val items = doc.select(".promo-item, .movie-item, .m-item, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("In Evidenza", items)), false)
    }

    // CORRETTO: Aggiunto parametro 'page' e cambiato tipo di ritorno
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // Usiamo la ricerca GET che hai indicato come funzionante
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query&search_start=$page"
        val doc = app.get(url).document
        
        val results = doc.select(".m-item, .movie-item, article, article.short").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return results as SearchResponseList?
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Proviamo a trovare il link più rilevante (titolo o immagine)
        val a = this.selectFirst("h2 a, h3 a, .m-title a, a[href*='/film/'], a[href*='/serie-tv/']") ?: this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        
        if (href.contains("/tags/") || href.contains("/category/") || href == "$mainUrl/") return null

        val title = this.selectFirst("h2, h3, .m-title, .story-heading")?.text() 
            ?: a.attr("title").ifEmpty { a.text() }
        
        if (title.isBlank()) return null

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
        val title = doc.selectFirst("h1, .story-heading")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst(".film-poster img, .m-img img, .story-cover img")?.attr("src"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content") 
            ?: doc.selectFirst(".story, #news-id")?.text()
        
        val isSerie = url.contains("/serie-tv/") || doc.select("#tv_tabs, .episodes-list").isNotEmpty()

        return if (isSerie) {
            val episodes = doc.select(".tt_series li, .episodes-list li, .story a[href*='episodio']").mapNotNull { li ->
                val link = if (li.tagName() == "a") li else li.selectFirst("a")
                if (link != null) {
                    val epData = link.attr("data-link").ifEmpty { link.attr("href") }
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
        if (data.startsWith("http") && !data.contains(mainUrl)) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

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
