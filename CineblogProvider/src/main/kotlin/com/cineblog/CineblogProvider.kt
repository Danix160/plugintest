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
        // La home usa GET
        val doc = app.get(mainUrl).document
        // Cerchiamo tutti i possibili contenitori di film
        val items = doc.select("article.short, .promo-item, .movie-item, .m-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("Novità", items)), false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val resultFrom = ((page - 1) * 10) + 1
        
        // Ricerca con POST come da file cerca.txt
        val response = app.post(
            "$mainUrl/index.php?do=search",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "search_start" to page.toString(),
                "full_search" to "0",
                "result_from" to resultFrom.toString(),
                "story" to query
            )
        )
        
        val doc = response.document
        val results = doc.select("article.short, .m-item, .movie-item, .story-heading").mapNotNull {
            // Se il selettore ha preso il titolo direttamente, risaliamo al parent
            if (it.hasClass("story-heading")) it.parent()?.toSearchResult() 
            else it.toSearchResult()
        }.distinctBy { it.url }

        return results as SearchResponseList?
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Cerchiamo il link del titolo: proviamo più combinazioni
        val a = this.selectFirst(".story-heading a, h2 a, h3 a, .m-title a, a") ?: return null
        val href = fixUrl(a.attr("href"))
        
        // Filtriamo link inutili
        if (href.contains("/tags/") || href.contains("/category/") || href == mainUrl) return null

        val title = a.text().trim().ifEmpty { this.selectFirst("img")?.attr("alt") } ?: return null
        if (title.isBlank()) return null

        // Cerchiamo la locandina: proviamo data-src (lazy load) o src standard
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
        val title = doc.selectFirst("h1, .story-heading, .m-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst(".story-cover img, .film-poster img, .m-img img")?.attr("src"))
        val plot = doc.selectFirst(".story, .m-desc, meta[name=description]")?.text() 
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        
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
        if (data.startsWith("http") && !data.contains(mainUrl)) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

        val doc = app.get(data).document
        doc.select("a[href*='mixdrop'], a[href*='supervideo'], a[href*='vidoza'], a[href*='streamtape']")
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
