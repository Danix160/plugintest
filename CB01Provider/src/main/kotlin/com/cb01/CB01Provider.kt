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
        listOf("$mainUrl/?s=", "$mainUrl/serietv/?s=").forEach { path ->
            try {
                app.get(path + query).document.select("div.card, div.post-item, article.card").forEach {
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
        
        val isTvSeries = url.contains("/serietv/") || document.selectFirst(".sp-wrap") != null

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // Ogni spoiler (sp-wrap) diventa una stagione
            document.select("div.sp-wrap").forEachIndexed { index, wrap ->
                val seasonNumber = index + 1
                val seasonName = wrap.selectFirst(".sp-head")?.text()?.trim() ?: "Stagione $seasonNumber"
                
                // Troviamo i link (es. quelli che portano a uprot)
                wrap.select(".sp-body a").forEach { a ->
                    val href = a.attr("href")
                    if (href.startsWith("http")) {
                        // Se il link è un aggregatore come uprot, Cloudstream caricherà gli episodi da lì
                        episodes.add(newEpisode(href) {
                            this.name = a.text().trim()
                            this.season = seasonNumber
                            this.episode = null // Lasciamo null se non conosciamo il numero esatto, o lo estraiamo dal testo
                        })
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
        // Se è un link diretto a un video host supportato
        if (loadExtractor(data, data, subtitleCallback, callback)) return true

        // Se è un link a uprot.net o simili, carichiamo la pagina per trovare gli episodi/video reali
        val doc = try { app.get(data).document } catch (e: Exception) { return false }
        
        val links = doc.select("iframe, a").mapNotNull { 
            it.attr("src").ifEmpty { it.attr("href") }.takeIf { s -> s.startsWith("http") }
        }.distinct()

        links.forEach { link ->
            // Se dentro uprot troviamo altri link (Mixdrop, Supervideo, ecc.)
            if (!link.contains(Regex("google|facebook|whatsapp|twitter|amazon|apple"))) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
