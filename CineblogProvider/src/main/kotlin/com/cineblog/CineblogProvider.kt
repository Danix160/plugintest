package com.cineblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.club"
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(mainUrl).document
        val items = doc.select(".promo-item, .movie-item, .m-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("In Evidenza", items)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        for (page in 1..5) {
            try {
                val pagedResults = app.post(
                    "$mainUrl/index.php?do=search",
                    data = mapOf(
                        "do" to "search",
                        "subaction" to "search",
                        "search_start" to "$page",
                        "full_search" to "0",
                        "result_from" to "${(page - 1) * 20 + 1}",
                        "story" to query
                    )
                ).document.select(".m-item, .movie-item, article").mapNotNull {
                    it.toSearchResult()
                }
                if (pagedResults.isEmpty()) break
                allResults.addAll(pagedResults)
            } catch (e: Exception) {
                break
            }
        }
        return allResults.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.contains("/tags/") || href.contains("/category/")) return null

        var title = this.selectFirst("h2, h3, .m-title")?.text() ?: a.attr("title").ifEmpty { a.text() }
        
        title = title.replace(Regex("""(?i)(\d+x\d+|Stagion[ei]\s+\d+|streaming)"""), "")
                     .replace(Regex("""[\-\s,._/]+$"""), "").trim()

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
        val isTv = href.contains("/serie-tv/") || title.contains("serie tv", true)

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        var title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        title = title.replace(Regex("""(?i)(\s+\d+x\d+.*|Stagion[ei]\s+\d+.*|streaming)"""), "")
                     .replace(Regex("""[\-\s,._/]+$"""), "").trim()
        
        val poster = fixUrlNull(
            doc.selectFirst("img._player-cover")?.attr("src") 
            ?: doc.selectFirst(".story-poster img, .m-img img, img[itemprop='image']")?.attr("src")
        )

        // --- PULIZIA TRAMA SUPER AVANZATA ---
        var rawPlot = doc.selectFirst("meta[name='description']")?.attr("content") ?: ""
        
        // 1. Rimuove pattern di spam specifici compresa la virgola finale
        var cleanPlot = rawPlot.replace(Regex("""(?i).*?(?:streaming|treaming).*?(?:serie tv|film).*?(?:cb01|cineblog\d*01)\s*[,.:;\-–]*"""), "")
                               .replace(Regex("""(?i)cineblog\d*01\s*[,.:;\-–]*"""), "")
                               .replace(Regex("""(?i)cb01\s*[,.:;\-–]*"""), "")
                               .replace(Regex("""^[\s,.:;–\-]+"""), "") // Pulisce i rimasugli all'inizio
                               .trim()

        // 2. Rimuove la ripetizione della trama (se il sito la incolla due volte)
        val half = cleanPlot.length / 2
        if (half > 10) {
            val firstHalf = cleanPlot.substring(0, half).trim()
            val secondHalf = cleanPlot.substring(half).trim()
            if (secondHalf.contains(firstHalf) || firstHalf.contains(secondHalf)) {
                cleanPlot = firstHalf
            }
        }

        val seasonContainer = doc.selectFirst(".tt_season")
        
        return if (seasonContainer != null) {
            val episodesList = mutableListOf<Episode>()
            val seasonPanes = doc.select(".tt_series .tab-content .tab-pane")
            
            seasonPanes.forEachIndexed { index, pane ->
                val seasonNum = index + 1
                pane.select("a[id^=serie-]").forEach { a ->
                    val epData = a.attr("data-link").ifEmpty { a.attr("href") }
                    val dataNum = a.attr("data-num")
                    
                    val epNum = if (dataNum.contains("x")) {
                        dataNum.substringAfter("x").toIntOrNull()
                    } else {
                        a.text().toIntOrNull()
                    } ?: 1

                    episodesList.add(newEpisode(fixUrl(epData)) {
                        this.name = "Episodio $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList.distinctBy { "${it.season}-${it.episode}" }) {
                this.posterUrl = poster
                this.plot = cleanPlot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = cleanPlot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http") && !data.contains("cineblog001") && !data.contains("mostraguarda")) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

        val doc = app.get(fixUrl(data)).document
        val realUrl = doc.selectFirst(".open-fake-url")?.attr("data-url")
            ?: doc.selectFirst("iframe[src*='mostraguarda']")?.attr("src")
            
        val targetDoc = if (!realUrl.isNullOrBlank()) app.get(fixUrl(realUrl)).document else doc

        targetDoc.select("li[data-link], a[data-link], a.mr, iframe#_player, iframe[src*='embed']").forEach { el ->
            val link = el.attr("data-link").ifEmpty { el.attr("src") }
            if (link.isNotBlank() && !link.contains("mostraguarda.stream") && !link.contains("facebook") && !link.contains("google")) {
                loadExtractor(fixUrl(link), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
