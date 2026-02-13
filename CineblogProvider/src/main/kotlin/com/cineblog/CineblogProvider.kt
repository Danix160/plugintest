package com.cineblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.club"
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(mainUrl).document [cite: 2]
        val items = doc.select(".promo-item, .movie-item, .m-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url } [cite: 2]

        return newHomePageResponse(listOf(HomePageList("In Evidenza", items)), false) [cite: 2]
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        // Carichiamo le prime 3 pagine in parallelo per simulare una ricerca profonda
        (1..3).map { page ->
            async {
                try {
                    app.post(
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
                } catch (e: Exception) {
                    emptyList<SearchResponse>()
                }
            }
        }.awaitAll().flatten().distinctBy { it.url } [cite: 3]
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null [cite: 4]
        val href = fixUrl(a.attr("href")) [cite: 4]
        
        if (href.contains("/tags/") || href.contains("/category/")) return null [cite: 4]

        val title = this.selectFirst("h2, h3, .m-title")?.text() 
            ?: a.attr("title").ifEmpty { a.text() } [cite: 4]
        
        if (title.isBlank()) return null [cite: 4]

        val img = this.selectFirst("img") [cite: 5]
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src")) [cite: 5]

        val isTv = href.contains("/serie-tv/") || title.contains("serie tv", true) [cite: 5, 6]

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            } [cite: 6]
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            } [cite: 6, 7]
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document [cite: 8]
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null [cite: 8]
        
        val poster = fixUrlNull(
            doc.selectFirst("img._player-cover")?.attr("src") 
            ?: doc.selectFirst(".story-poster img, .m-img img, img[itemprop='image']")?.attr("src")
        ) [cite: 8]
        
        val plot = doc.selectFirst("meta[name='description']")?.attr("content") [cite: 9]
        val isSerie = url.contains("/serie-tv/") || doc.select("#tv_tabs").isNotEmpty() [cite: 9, 10]

        return if (isSerie) {
            val episodes = doc.select(".tt_series li, .episodes-list li").mapNotNull { li ->
                val link = li.selectFirst("a")
                if (link != null) {
                    val epData = link.attr("data-link").ifEmpty { link.attr("href") }
                    newEpisode(fixUrl(epData)) { this.name = link.text().trim() } [cite: 10, 11]
                } else null
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            } [cite: 11, 12]
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            } [cite: 12, 13]
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
        } [cite: 14, 15]

        var doc = app.get(fixUrl(data)).document [cite: 15]
        
        val realUrl = doc.selectFirst(".open-fake-url")?.attr("data-url")
            ?: doc.selectFirst("iframe[src*='mostraguarda']")?.attr("src")
            
        if (!realUrl.isNullOrBlank()) {
            doc = app.get(fixUrl(realUrl)).document
        } [cite: 16, 17]

        doc.select("li[data-link], a[data-link]").forEach { el ->
            val link = el.attr("data-link")
            if (link.isNotBlank() && !link.contains("mostraguarda.stream")) {
                loadExtractor(fixUrl(link), data, subtitleCallback, callback)
            }
        } [cite: 17, 18]

        doc.select("iframe#_player, iframe[src*='embed']").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("google")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        } [cite: 19]

        return true
    }
}
