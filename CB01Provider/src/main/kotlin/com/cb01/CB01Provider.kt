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

    override val mainPage = mainPageOf(
        mainUrl to "Film",
        "$mainUrl/serietv" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val home = document.select("div.card, div.post-item, article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        val searchPaths = listOf("$mainUrl/?s=", "$mainUrl/serietv/?s=")
        
        searchPaths.forEach { path ->
            try {
                val doc = app.get(path + query).document
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

        return if (href.contains("/serietv/") || title.contains("Serie TV", true)) {
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
        val plot = document.selectFirst(".entry-content p, .card-text")?.text()
        
        val isTvSeries = url.contains("/serietv/") || document.selectFirst(".sp-wrap, ul.episodi") != null

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // 1. GESTIONE SPOILER (sp-wrap) - Come nel tuo esempio
            document.select("div.sp-wrap").forEach { wrap ->
                val seasonName = wrap.selectFirst(".sp-head")?.text()?.trim() ?: "Serie"
                wrap.select(".sp-body a").forEach { a ->
                    val epHref = a.attr("href")
                    val hostName = a.text().trim()
                    if (epHref.startsWith("http")) {
                        episodes.add(newEpisode(epHref) {
                            this.name = "$seasonName - $hostName"
                        })
                    }
                }
            }

            // 2. GESTIONE LISTE CLASSICHE (se presenti)
            document.select("ul.episodi li a, .entry-content p a").forEach { a ->
                val epHref = a.attr("href")
                val epName = a.text().trim()
                // Evitiamo di duplicare se già preso dagli spoiler
                if (epHref.startsWith("http") && episodes.none { it.data == epHref }) {
                    if (epName.contains(Regex("\\d+x\\d+|Episodio|Stagione|Streaming", RegexOption.IGNORE_CASE))) {
                        episodes.add(newEpisode(epHref) { this.name = epName })
                    }
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
        val document = app.get(data).document
        // CB01 a volte mette l'iframe direttamente nella pagina dell'episodio
        document.select("iframe, a.btn-download, .sp-body a").forEach {
            val link = it.attr("src").ifEmpty { it.attr("href") }
            if (link.contains("http") && !link.contains("google") && !link.contains("facebook")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
