package com.cb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class CBProvider : MainAPI() {
    override var mainUrl = "https://cb001.uno"
    override var name = "CB01"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Funzione helper per pulire il parsing dei singoli elementi (Film/Serie)
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, .post-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        // Determina se è una serie dal tag o dall'URL
        val type = if (href.contains("-serie") || this.select(".category").text().contains("Serie", true)) 
            TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        val home = document.select("div.post-item, article.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse("Ultime Aggiunte", home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.post-item, article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, meta[property='og:title']")?.text()?.trim() ?: ""
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = document.selectFirst("div.p-text, .story")?.text()?.trim()

        val episodes = mutableListOf<Episode>()

        // LOGICA PER SERIE TV (Basata sul tuo snippet HTML)
        val seasonTabs = document.select("div.tab-pane")
        if (seasonTabs.isNotEmpty()) {
            seasonTabs.forEach { seasonPane ->
                val seasonId = seasonPane.attr("id").replace("season-", "").toIntOrNull() ?: 1
                
                seasonPane.select("ul li").forEach { li ->
                    val mainAnchor = li.selectFirst("a[data-link]") ?: return@forEach
                    val epData = mainAnchor.attr("data-num") // Es: "1x1"
                    val epNum = epData.split("x").lastOrNull()?.toIntOrNull()
                    val epTitle = mainAnchor.attr("data-title")

                    // Raccogliamo i link dei mirror (Dropload, Supervideo, ecc.)
                    val linksList = li.select("div.mirrors a").map { it.attr("data-link") }
                        .filter { it.isNotBlank() }
                        .joinToString(",")

                    episodes.add(newEpisode(linksList) {
                        this.name = epTitle
                        this.season = seasonId
                        this.episode = epNum
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // LOGICA PER FILM (Se non ci sono tab episodi)
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Se 'data' contiene più link separati da virgola (nostra logica serie TV)
        if (data.contains(",")) {
            data.split(",").forEach { link ->
                loadExtractor(link, mainUrl, subtitleCallback, callback)
            }
        } else {
            // Se è un film, cerchiamo i link nella pagina
            val document = app.get(data).document
            document.select("a.opbtn, .video-link a").forEach {
                val link = it.attr("data-link").ifBlank { it.attr("href") }
                loadExtractor(link, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
