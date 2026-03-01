package com.cineblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.autos"
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageList = mutableListOf<HomePageList>()
        val mainDoc = app.get(mainUrl).document

        // In Evidenza
        val featured = mainDoc.select(".promo-item, .m-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (featured.isNotEmpty()) homePageList.add(HomePageList("In Evidenza", featured))

        // Ultimi Aggiunti
        val latest = mainDoc.select(".block-th").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (latest.isNotEmpty()) homePageList.add(HomePageList("Ultimi Aggiunti", latest))

        // Serie TV
        try {
            val tvDoc = app.get("$mainUrl/serie-tv/").document
            val tvItems = tvDoc.select(".block-th, .movie-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (tvItems.isNotEmpty()) homePageList.add(HomePageList("Serie TV", tvItems))
        } catch (e: Exception) { }

        return newHomePageResponse(homePageList, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        // Caricamento esteso a 5 pagine come richiesto
        for (page in 1..5) {
            try {
                val doc = app.post(
                    "$mainUrl/index.php?do=search",
                    data = mapOf(
                        "do" to "search",
                        "subaction" to "search",
                        "search_start" to "$page",
                        "full_search" to "0",
                        "result_from" to "${(page - 1) * 20 + 1}",
                        "story" to query
                    )
                ).document
                
                val pagedResults = doc.select(".m-item, .movie-item, article, .block-th").mapNotNull { it.toSearchResult() }
                if (pagedResults.isEmpty()) break
                allResults.addAll(pagedResults)
            } catch (e: Exception) { break }
        }
        return allResults.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.contains("/tags/") || href.contains("/category/")) return null

        var title = this.selectFirst(".block-th-haeding, h2, h3, .m-title")?.text() ?: a.attr("title").ifEmpty { a.text() }
        title = title.replace(Regex("""(?i)(\[.*?\]|\d+x\d+|Stagion[ei]\s+\d+|streaming|\(\d{4}.*?\))"""), "").trim()

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: img?.attr("src"))
        val isTv = href.contains("/serie-tv/") || this.selectFirst(".se_num") != null

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("h1")?.text() ?: return null
        val title = rawTitle.replace(Regex("""(?i)(\[.*?\]|\d+x\d+|Stagion[ei]\s+\d+|streaming|\(\d{4}.*?\))"""), "").trim()
        
        val posterElement = doc.selectFirst(".story-cover img, img[itemprop='image'], .m-img img")
        val poster = fixUrlNull(posterElement?.attr("data-src")?.ifEmpty { posterElement.attr("src") })

        val plot = doc.selectFirst(".story.space-sm")?.let {
            val temp = it.clone()
            temp.select("strong, a").remove()
            temp.text().trim()
        }

        val seasonContainer = doc.selectFirst(".tt_series")
        
        return if (seasonContainer != null) {
            val episodesList = mutableListOf<Episode>()
            doc.select(".tt_series .tab-content .tab-pane").forEachIndexed { index, pane ->
                val seasonNum = index + 1
                pane.select("li").forEach { li ->
                    val a = li.selectFirst("a") ?: return@forEach
                    val mainLink = a.attr("data-link").ifEmpty { a.attr("href") }
                    val mirrors = li.select(".mirrors a.mr").map { it.attr("data-link") }
                    val allLinks = (listOf(mainLink) + mirrors).filter { it.isNotBlank() }.joinToString("|")

                    val epNum = a.attr("data-num").substringAfter("x").filter { it.isDigit() }.toIntOrNull() ?: 1

                    episodesList.add(newEpisode(allLinks) {
                        this.name = "Episodio $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
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
        data.split("|").filter { it.isNotBlank() }.forEach { link ->
            val cleanLink = fixUrl(link)
            try {
                if (cleanLink.startsWith("http") && !cleanLink.contains("cineblog001") && !cleanLink.contains("mostraguarda")) {
                    loadExtractor(cleanLink, mainUrl, subtitleCallback, callback)
                } else {
                    val doc = app.get(cleanLink).document
                    // Gestione specifica Iframe per Dropload
                    doc.select("iframe").forEach { iframe ->
                        val src = fixUrl(iframe.attr("src"))
                        if (src.contains("dropload") || src.contains("supervideo") || src.contains("mixdrop")) {
                            try {
                                loadExtractor(src, mainUrl, subtitleCallback, callback)
                            } catch (e: Exception) { }
                        }
                    }
                    // Mirror secondari
                    doc.select("[data-link], a[href*='dropload']").forEach { el ->
                        val mirror = el.attr("data-link").ifEmpty { el.attr("href") }
                        if (mirror.isNotBlank() && !mirror.contains("mostraguarda")) {
                            try {
                                loadExtractor(fixUrl(mirror), mainUrl, subtitleCallback, callback)
                            } catch (e: Exception) { }
                        }
                    }
                }
            } catch (e: Exception) { }
        }
        return true
    }
}
