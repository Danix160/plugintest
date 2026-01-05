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
            
            // 1. Estrazione dagli SPOILER (classico delle serie TV su CB01)
            document.select("div.sp-wrap").forEach { wrap ->
                val seasonName = wrap.selectFirst(".sp-head")?.text()?.trim() ?: "Serie"
                wrap.select(".sp-body a").forEach { a ->
                    val epHref = a.attr("href")
                    val hostLabel = a.text().trim()
                    if (epHref.startsWith("http")) {
                        episodes.add(newEpisode(epHref) {
                            this.name = "$seasonName - $hostLabel"
                        })
                    }
                }
            }

            // 2. Estrazione da link liberi nel testo (se non già presi)
            document.select(".entry-content a").forEach { a ->
                val href = a.attr("href")
                val name = a.text()
                if (href.startsWith("http") && episodes.none { it.data == href }) {
                    // Prendi link che sembrano episodi o che puntano a uprot/maxstream
                    if (name.contains(Regex("\\d+", RegexOption.IGNORE_CASE)) || href.contains("uprot") || href.contains("maxstream")) {
                        episodes.add(newEpisode(href) { this.name = name })
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
        // Se il link è già un video diretto o un host supportato (Mixdrop, ecc.), loadExtractor lo gestisce
        if (loadExtractor(data, data, subtitleCallback, callback)) return true

        // Altrimenti, carichiamo la pagina (uprot, maxstream, o CB01 stessa) per cercare iframe o altri link
        val doc = try { app.get(data).document } catch (e: Exception) { return false }
        
        // Cerchiamo tutti i link e iframe nella pagina caricata
        val potentialLinks = doc.select("iframe, a").mapNotNull { 
            it.attr("src").ifEmpty { it.attr("href") }.takeIf { s -> s.startsWith("http") }
        }.distinct()

        potentialLinks.forEach { link ->
            // Se troviamo un link a uprot/maxstream dentro un'altra pagina, facciamo un altro salto
            if (link.contains("uprot.net") || link.contains("maxstream") || link.contains("akvideo")) {
                val subDoc = app.get(link).document
                subDoc.select("iframe, a").forEach { subA ->
                    val finalLink = subA.attr("src").ifEmpty { subA.attr("href") }
                    if (finalLink.startsWith("http")) {
                        loadExtractor(finalLink, link, subtitleCallback, callback)
                    }
                }
            } else {
                // Pulizia link social/pubblicità
                if (!link.contains(Regex("google|facebook|whatsapp|twitter|pinterest"))) {
                    loadExtractor(link, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
