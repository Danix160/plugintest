package com.cb01

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.newHomePageResponse
import org.jsoup.nodes.Element

class CB01Provider : MainAPI() {
    override var mainUrl = "https://cb01net.baby"
    override var name = "CB01"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        mainUrl to "Film",
        "$mainUrl/serietv" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        val home = document.select("div.card, div.post-item, article.card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        listOf("$mainUrl/?s=$query", "$mainUrl/serietv/?s=$query").forEach { path ->
            try {
                app.get(path, headers = commonHeaders).document.select("div.card, div.post-item, article.card").forEach {
                    it.toSearchResult()?.let { result -> allResults.add(result) }
                }
            } catch (e: Exception) { }
        }
        return allResults
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".card-title a, h2 a, .post-title a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val img = this.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: img?.attr("src")

        return if (href.contains("/serietv/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val title = document.selectFirst("h1.entry-title, h1.card-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".card-image img, .poster img, .entry-content img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }
        
        val episodes = mutableListOf<Episode>()
        document.select("div.sp-wrap").forEachIndexed { index, wrap ->
            val seasonNum = index + 1
            wrap.select(".sp-body a").forEach { a ->
                val href = a.attr("href")
                if (href.contains(Regex("maxstream|uprot|akvideo|delta|mixdrop|vidoza"))) {
                    try {
                        val listDoc = app.get(href, headers = commonHeaders).document
                        listDoc.select("table td a, .list-group-item a").forEach { ep ->
                            val epUrl = ep.attr("href")
                            val epText = ep.text().trim()
                            if (epUrl.isNotEmpty() && epUrl.startsWith("http")) {
                                episodes.add(newEpisode(epUrl) {
                                    this.name = "Stagione $seasonNum - Ep. $epText"
                                    this.season = seasonNum
                                })
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Proviamo il link diretto
        loadExtractor(data, data, subtitleCallback, callback)

        val doc = try { app.get(data, headers = commonHeaders).document } catch (e: Exception) { return false }

        // Cerca iframe
        doc.select("iframe").forEach {
            val src = it.attr("src")
            if (src.startsWith("http") && !src.contains("google")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // Cerca link nei bottoni
        doc.select("a.btn, .download-link a, .sp-body a").forEach {
            val link = it.attr("href")
            if (link.startsWith("http") && !link.contains("cb01")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
