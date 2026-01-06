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
            
            // 1. Cerchiamo gli spoiler (Stagioni)
            val spoilers = document.select("div.sp-wrap")
            
            for ((index, wrap) in spoilers.withIndex()) {
                val seasonHeader = wrap.selectFirst(".sp-head")?.text()?.trim() ?: "Stagione ${index + 1}"
                val seasonNum = index + 1
                
                // 2. Cerchiamo i link come Maxstream/Uprot
                val links = wrap.select(".sp-body a")
                for (a in links) {
                    val uprotUrl = a.attr("href")
                    
                    if (uprotUrl.contains("uprot.net") || uprotUrl.contains("maxstream") || uprotUrl.contains("akvideo")) {
                        try {
                            // CARICAMENTO PROFONDO: Entriamo in uprot per prendere la lista
                            val uprotPage = app.get(uprotUrl).document
                            
                            // 3. Cerchiamo i link agli episodi reali nella tabella di uprot
                            // Di solito sono dentro <a> che contengono il numero dell'episodio
                            val realEpLinks = uprotPage.select("a[href*='/msfld/'], a[href*='/v/'], table a")
                            
                            realEpLinks.forEach { epLink ->
                                val finalHref = epLink.attr("href")
                                val epName = epLink.text().trim()
                                
                                if (finalHref.startsWith("http") && epName.isNotEmpty()) {
                                    episodes.add(newEpisode(finalHref) {
                                        this.name = epName
                                        this.season = seasonNum
                                        this.description = seasonHeader
                                    })
                                }
                            }
                        } catch (e: Exception) { 
                            // Fallback se uprot non risponde
                        }
                    }
                }
            }

            // Se non abbiamo trovato nulla con uprot, aggiungiamo i link dello spoiler come episodi diretti
            if (episodes.isEmpty()) {
                document.select("div.sp-wrap").forEachIndexed { idx, wrap ->
                    wrap.select(".sp-body a").forEach { a ->
                        episodes.add(newEpisode(a.attr("href")) {
                            this.name = a.text()
                            this.season = idx + 1
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
        if (loadExtractor(data, data, subtitleCallback, callback)) return true

        val doc = try { app.get(data).document } catch (e: Exception) { return false }
        
        // Cerca iframe o link finali (Mixdrop, Supervideo ecc.)
        doc.select("iframe, a.btn, .download-link a").forEach {
            val link = it.attr("src").ifEmpty { it.attr("href") }
            if (link.startsWith("http") && !link.contains("google")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
