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

        var rawPlot = doc.selectFirst("meta[name='description']")?.attr("content") ?: ""
        var cleanPlot = rawPlot.replace(Regex("""(?i).*?(?:streaming|treaming).*?(?:serie tv|film).*?(?:cb01|cineblog\d*01)\s*[,.:;\-–]*"""), "")
                               .replace(Regex("""(?i)cineblog\d*01\s*[,.:;\-–]*"""), "")
                               .replace(Regex("""(?i)cb01\s*[,.:;\-–]*"""), "")
                               .replace(Regex("""^[\s,.:;–\-]+"""), "")
                               .trim()

        val half = cleanPlot.length / 2
        if (half > 15) {
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
                pane.select("li").forEach { li ->
                    val a = li.selectFirst("a[id^=serie-]") ?: return@forEach
                    
                    // Prendiamo il link principale E quelli nei mirror (come Dropload)
                    val mainLink = a.attr("data-link").ifEmpty { a.attr("href") }
                    val mirrors = li.select(".mirrors a.mr").map { it.attr("data-link") }
                    
                    // Uniamo i link separandoli con una virgola o un carattere speciale 
                    // per far sì che loadLinks possa leggerli tutti
                    val allLinks = (listOf(mainLink) + mirrors).filter { it.isNotBlank() }.joinToString("|")

                    val dataNum = a.attr("data-num")
                    val epNum = if (dataNum.contains("x")) {
                        dataNum.substringAfter("x").toIntOrNull()
                    } else {
                        a.text().toIntOrNull()
                    } ?: 1

                    episodesList.add(newEpisode(allLinks) {
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
        // Separiamo i mirror che abbiamo unito nel metodo load
        val links = data.split("|")

        links.forEach { link ->
            if (link.startsWith("http") && !link.contains("cineblog001") && !link.contains("mostraguarda")) {
                loadExtractor(link, link, subtitleCallback, callback)
            } else {
                val doc = app.get(fixUrl(link)).document
                val realUrl = doc.selectFirst(".open-fake-url")?.attr("data-url")
                    ?: doc.selectFirst("iframe[src*='mostraguarda']")?.attr("src")
                    
                val targetDoc = if (!realUrl.isNullOrBlank()) app.get(fixUrl(realUrl)).document else doc

                targetDoc.select("li[data-link], a[data-link], a.mr, iframe#_player, iframe[src*='embed']").forEach { el ->
                    val mirror = el.attr("data-link").ifEmpty { el.attr("src") }
                    if (mirror.isNotBlank() && !mirror.contains("mostraguarda.stream") && !mirror.contains("facebook") && !mirror.contains("google")) {
                        loadExtractor(fixUrl(mirror), link, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }
}
