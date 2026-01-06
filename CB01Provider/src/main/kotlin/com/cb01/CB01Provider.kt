package com.cb01

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

    // Definiamo le sezioni della Home
    override val mainPage = mainPageOf(
        mainUrl to "Film",
        "$mainUrl/serietv" to "Serie TV"
    )

    // FIX: Implementazione corretta di getMainPage
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val home = document.select("div.card, div.post-item, article.card").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // FIX: Implementazione corretta di search
    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        // Cerchiamo sia nei film che nelle serie
        val searchUrls = listOf("$mainUrl/?s=$query", "$mainUrl/serietv/?s=$query")
        
        for (url in searchUrls) {
            try {
                val doc = app.get(url).document
                doc.select("div.card, div.post-item, article.card").forEach {
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
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1.card-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".card-image img, .poster img, .entry-content img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }
        
        val isTvSeries = url.contains("/serietv/") || document.selectFirst(".sp-wrap") != null

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            // Trasformiamo gli spoiler in stagioni
            document.select("div.sp-wrap").forEachIndexed { index, wrap ->
                val seasonName = wrap.selectFirst(".sp-head")?.text()?.trim() ?: "Stagione ${index + 1}"
                wrap.select(".sp-body a").forEach { a ->
                    val link = a.attr("href")
                    if (link.startsWith("http")) {
                        episodes.add(newEpisode(link) {
                            this.name = "$seasonName - ${a.text()}"
                            this.season = index + 1
                        })
                    }
                }
            }
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
        // Se è un link a uprot/maxstream, carichiamo la pagina interna
        if (data.contains("uprot.net") || data.contains("maxstream")) {
            val doc = app.get(data).document
            doc.select("a, iframe").forEach {
                val link = it.attr("href").ifEmpty { it.attr("src") }
                if (link.startsWith("http") && !link.contains("cb01")) {
                    loadExtractor(link, data, subtitleCallback, callback)
                }
            }
        } else {
            loadExtractor(data, data, subtitleCallback, callback)
        }
        return true
    }
}
