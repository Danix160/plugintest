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
        val items = doc.select(".promo-item, .movie-item, .m-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("In Evidenza", items)), false)
    }

   override suspend fun search(query: String): List<SearchResponse> {
    val allResults = mutableListOf<SearchResponse>()
    
    // Ciclo per caricare le prime 3 pagine del sito cineblog
    for (page in 1..3) {
        try {
            val pagedResults = app.post(
                "$mainUrl/index.php?do=search",
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "search_start" to "$page",
                    "full_search" to "0",
                    "result_from" to "${(page - 1) * 20 + 1}",
                    "story" to query
                )
            ).document.select(".m-item, .movie-item, article").mapNotNull {
                it.toSearchResult()
            }
            
            if (pagedResults.isEmpty()) break // Se una pagina è vuota, si ferma
            allResults.addAll(pagedResults)
        } catch (e: Exception) {
            break
        }
    }
    
    return allResults.distinctBy { it.url }
}
    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        
        if (href.contains("/tags/") || href.contains("/category/")) return null

        val title = this.selectFirst("h2, h3, .m-title")?.text() 
            ?: a.attr("title").ifEmpty { a.text() }
        
        if (title.isBlank()) return null

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
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        
        val poster = fixUrlNull(
            doc.selectFirst("img._player-cover")?.attr("src") 
            ?: doc.selectFirst(".story-poster img, .m-img img, img[itemprop='image']")?.attr("src")
        )
        
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")
        val isSerie = url.contains("/serie-tv/") || doc.select("#tv_tabs").isNotEmpty()

        return if (isSerie) {
            val episodes = doc.select(".tt_series li, .episodes-list li").mapNotNull { li ->
                val link = li.selectFirst("a")
                if (link != null) {
                    val epData = link.attr("data-link").ifEmpty { link.attr("href") }
                    newEpisode(fixUrl(epData)) { this.name = link.text().trim() }
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
        if (data.startsWith("http") && !data.contains("cineblog001") && !data.contains("mostraguarda")) {
            loadExtractor(data, data, subtitleCallback, callback)
        }

        var doc = app.get(fixUrl(data)).document
        
        val realUrl = doc.selectFirst(".open-fake-url")?.attr("data-url")
            ?: doc.selectFirst("iframe[src*='mostraguarda']")?.attr("src")
            
        if (!realUrl.isNullOrBlank()) {
            doc = app.get(fixUrl(realUrl)).document
        }

        doc.select("li[data-link], a[data-link]").forEach { el ->
            val link = el.attr("data-link")
            if (link.isNotBlank() && !link.contains("mostraguarda.stream")) {
                loadExtractor(fixUrl(link), data, subtitleCallback, callback)
            }
        }

        doc.select("iframe#_player, iframe[src*='embed']").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("google")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
