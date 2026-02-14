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

        val seasonContainer = doc.selectFirst(".tt_season")
        
        return if (seasonContainer != null) {
            val episodesList = mutableListOf<Episode>()
            val seasonPanes = doc.select(".tt_series .tab-content .tab-pane")
            
            seasonPanes.forEachIndexed { index, pane ->
                val seasonNum = index + 1
                pane.select("li").forEach { li ->
                    val a = li.selectFirst("a[id^=serie-]") ?: return@forEach
                    val mainLink = a.attr("data-link").ifEmpty { a.attr("href") }
                    val mirrors = li.select(".mirrors a.mr").map { it.attr("data-link") }
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
        val links = data.split("|")

        links.forEach { link ->
            val cleanLink = fixUrl(link)
            if (cleanLink.isBlank()) return@forEach

            // 1. Se è un link diretto (es. mixdrop, dropload)
            if (!cleanLink.contains("cineblog001") && !cleanLink.contains("mostraguarda") && cleanLink.startsWith("http")) {
                loadExtractor(cleanLink, mainUrl, subtitleCallback, callback)
            } else {
                // 2. Cerchiamo i mirror nella pagina (Serie o Film)
                val doc = try { app.get(cleanLink).document } catch (e: Exception) { null } ?: return@forEach
                
                // Selettore specifico per i mirror dei film che hai mandato
                val mirrorElements = doc.select("._player-mirrors li, ._hidden-mirrors li, a.mr, li[data-link]")
                
                mirrorElements.forEach { el ->
                    val mLink = el.attr("data-link")
                    if (mLink.isNotBlank()) {
                        val fixedMLink = fixUrl(mLink)
                        
                        if (fixedMLink.contains("mostraguarda")) {
                            val innerDoc = try { app.get(fixedMLink).document } catch (e: Exception) { null }
                            val finalUrl = innerDoc?.selectFirst(".open-fake-url")?.attr("data-url")
                                ?: innerDoc?.selectFirst("iframe")?.attr("src")
                            if (!finalUrl.isNullOrBlank()) {
                                loadExtractor(fixUrl(finalUrl), mainUrl, subtitleCallback, callback)
                            }
                        } else if (!fixedMLink.contains("facebook") && !fixedMLink.contains("google")) {
                            loadExtractor(fixedMLink, mainUrl, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
        return true
    }
}
