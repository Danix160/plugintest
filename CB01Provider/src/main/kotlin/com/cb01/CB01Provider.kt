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
        // Cerchiamo in entrambi i database (Film e Serie)
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
            
            // Gestione dei blocchi Spoiler (Stagioni)
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

            // Aggiunta link liberi nella pagina (se non già presenti)
            document.select(".entry-content p a, .entry-content li a").forEach { a ->
                val href = a.attr("href")
                val text = a.text()
                if (href.contains("http") && episodes.none { it.data == href }) {
                    if (text.contains(Regex("\\d+x\\d+|Episodio|Stagione|Streaming", RegexOption.IGNORE_CASE))) {
                        episodes.add(newEpisode(href) { this.name = text })
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
        // 1. Carichiamo la pagina (potrebbe essere CB01 o l'intermedio come uprot)
        val doc = app.get(data).document
        
        // 2. Cerchiamo tutti i possibili link video (iframe o anchor)
        val sources = doc.select("iframe, a").mapNotNull { 
            it.attr("src").ifEmpty { it.attr("href") }.takeIf { s -> s.startsWith("http") }
        }.distinct()

        sources.forEach { link ->
            // Se è un link intermedio (uprot, maxstream, ecc.), lo carichiamo ricorsivamente
            if (link.contains("uprot.net") || link.contains("maxstream")) {
                val subDoc = app.get(link).document
                subDoc.select("iframe, a").forEach { subElement ->
                    val finalLink = subElement.attr("src").ifEmpty { subElement.attr("href") }
                    if (finalLink.startsWith("http")) {
                        loadExtractor(finalLink, link, subtitleCallback, callback)
                    }
                }
            } else {
                // Altrimenti proviamo l'estrazione diretta
                if (!link.contains("google") && !link.contains("facebook") && !link.contains("whatsapp")) {
                    loadExtractor(link, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
