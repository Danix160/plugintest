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
        val home = document.select("div.card, div.post-item, article.card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        listOf("$mainUrl/?s=$query", "$mainUrl/serietv/?s=$query").forEach { path ->
            try {
                app.get(path).document.select("div.card, div.post-item, article.card").forEach {
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
            
            // Analizziamo ogni Spoiler come una Stagione
            document.select("div.sp-wrap").forEachIndexed { index, wrap ->
                val seasonHeader = wrap.selectFirst(".sp-head")?.text()?.trim() ?: "Stagione ${index + 1}"
                val seasonNum = index + 1
                
                // Cerchiamo i link dentro lo spoiler (che mandano a uprot)
                wrap.select(".sp-body a").forEach { a ->
                    val uprotUrl = a.attr("href")
                    
                    if (uprotUrl.contains("uprot.net") || uprotUrl.contains("maxstream")) {
                        try {
                            // Carichiamo la pagina di uprot per vedere gli episodi reali
                            val uprotPage = app.get(uprotUrl).document
                            
                            // Cerchiamo tutti i link agli episodi (spesso sono in una tabella o lista)
                            uprotPage.select("a").forEach { epLink ->
                                val finalHref = epLink.attr("href")
                                val epName = epLink.text().trim()
                                
                                // Filtriamo i link che sembrano veri episodi
                                if (finalHref.startsWith("http") && (epName.contains(Regex("\\d+")) || epName.lowercase().contains("episodio"))) {
                                    episodes.add(newEpisode(finalHref) {
                                        this.name = epName
                                        this.season = seasonNum
                                        // Mostriamo il nome della stagione nel nome episodio per chiarezza
                                        this.episode = null 
                                        this.description = seasonHeader
                                    })
                                }
                            }
                        } catch (e: Exception) {
                            // Se non riesce a leggere uprot, aggiunge il link base come fallback
                            episodes.add(newEpisode(uprotUrl) {
                                this.name = a.text()
                                this.season = seasonNum
                            })
                        }
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
        // Se il link è un video diretto, l'extractor lo prende
        if (loadExtractor(data, data, subtitleCallback, callback)) return true

        // Se è rimasto un link di una pagina che contiene il video (es. Mixdrop)
        val doc = try { app.get(data).document } catch (e: Exception) { return false }
        doc.select("iframe, a").forEach {
            val link = it.attr("src").ifEmpty { it.attr("href") }
            if (link.startsWith("http") && !link.contains("google")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
