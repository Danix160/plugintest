package com.cb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class CBProvider : MainAPI() { 
    override var mainUrl = "https://cb001.uno"
    override var name = "CB"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Limitiamo il timeout per evitare che l'app si blocchi se il sito è lento
        val document = app.get(url = if (page <= 1) mainUrl else "$mainUrl/page/$page/", timeout = 15000).document
        
        // Ottimizzazione memoria: prendiamo solo i primi 20/25 elementi
        val home = document.select("div.post-item, article.post").take(24).mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse("Ultime Aggiunte", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, timeout = 15000).document
        return document.select("div.post-item, article.post").take(20).mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, .post-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        // Determina il tipo basandosi sulla categoria o URL
        val type = if (href.contains("-serie") || this.select(".category").text().contains("Serie", true)) 
            TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.cleanTitle() ?: return null
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = document.selectFirst("div.p-text, .story")?.text()?.trim()

        // Controllo se è una serie TV cercando la struttura delle stagioni
        val isSeries = url.contains("-serie") || document.select("div.tabs-box").isNotEmpty()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            // Parsing delle stagioni/episodi (basato su div.tab-pane)
            document.select("div.tab-pane").forEach { seasonPane ->
                val seasonNum = seasonPane.attr("id").filter { it.isDigit() }.toIntOrNull() ?: 1
                seasonPane.select("ul li").forEach { li ->
                    val linkData = li.selectFirst("a[data-link]")
                    val epName = linkData?.attr("data-title") ?: li.text()
                    val epNum = linkData?.attr("data-num")?.split("x")?.lastOrNull()?.toIntOrNull()

                    // Raccogliamo i link multipli (mirrors) separati da virgola
                    val mirrors = li.select("div.mirrors a").mapNotNull { it.attr("data-link") }
                        .filter { it.isNotEmpty() }
                        .joinToString(",")

                    if (mirrors.isNotEmpty()) {
                        episodes.add(newEpisode(mirrors) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // È un film: cerchiamo i pulsanti .opbtn che contengono i link
            val movieLinks = document.select("a.opbtn").mapNotNull { it.attr("data-link") }
                .filter { it.isNotEmpty() }
                .joinToString(",")

            return newMovieLoadResponse(title, url, TvType.Movie, movieLinks) {
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
        // Separiamo i mirror salvati come stringa separata da virgole
        data.split(",").forEach { link ->
            val cleanLink = if (link.startsWith("//")) "https:$link" else link
            loadExtractor(cleanLink, mainUrl, subtitleCallback, callback)
        }
        return true
    }

    private fun String.cleanTitle(): String {
        return this.replace("Streaming HD Gratis", "")
            .replace(" streaming", "")
            .trim()
    }
}
