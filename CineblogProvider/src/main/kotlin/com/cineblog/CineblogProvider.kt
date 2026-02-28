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

        val featured = mainDoc.select(".promo-item, .m-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (featured.isNotEmpty()) homePageList.add(HomePageList("In Evidenza", featured))

        val latest = mainDoc.select(".block-th").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (latest.isNotEmpty()) homePageList.add(HomePageList("Ultimi Aggiunti", latest))

        try {
            val animationDoc = app.get("$mainUrl/film/?genere=2").document
            val animationItems = animationDoc.select(".block-th, .movie-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (animationItems.isNotEmpty()) homePageList.add(HomePageList("Animazione", animationItems))
        } catch (e: Exception) { }

        return newHomePageResponse(homePageList, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
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
                
                val pagedResults = doc.select(".m-item, .movie-item, article, .block-th").mapNotNull {
                    it.toSearchResult()
                }
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

        var title = this.selectFirst(".block-th-haeding, h2, h3, .m-title")?.text() 
                    ?: a.attr("title").ifEmpty { a.text() }
        
        title = title.replace(Regex("""(?i)(\[.*?\]|\d+x\d+|Stagion[ei]\s+\d+|streaming|\(\d{4}\))"""), "")
                     .replace(Regex("""[\-\s,._/]+$"""), "").trim()

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: img?.attr("src"))
        val isTv = href.contains("/serie-tv/") || title.contains("serie tv", true)

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val rawTitle = doc.selectFirst("h1")?.text() ?: return null
        val title = rawTitle.replace(Regex("""(?i)(\[.*?\]|\d+x\d+|Stagion[ei]\s+\d+|streaming|\(\d{4}\))"""), "")
                            .replace(Regex("""[\-\s,._/]+$"""), "").trim()
        
        val posterElement = doc.selectFirst(".story-cover img, img[itemprop='image'], .story-poster img, .m-img img")
        val poster = fixUrlNull(posterElement?.attr("data-src")?.ifEmpty { posterElement.attr("src") } ?: posterElement?.attr("src"))

        val plot = doc.selectFirst("meta[name='description']")?.attr("content") ?:
                   doc.selectFirst("meta[property='og:description']")?.attr("content") ?:
                   doc.selectFirst(".story-text, .m-desc")?.text()

        val seasonContainer = doc.selectFirst(".tt_season, .tt_series")
        
        return if (seasonContainer != null) {
            val episodesList = mutableListOf<Episode>()
            doc.select(".tt_series .tab-content .tab-pane").forEachIndexed { index, pane ->
                val seasonNum = index + 1
                pane.select("li").forEach { li ->
                    val a = li.selectFirst("a") ?: return@forEach
                    val mainLink = a.attr("data-link").ifEmpty { a.attr("href") }
                    val mirrors = li.select(".mirrors a.mr").map { it.attr("data-link") }
                    val allLinks = (listOf(mainLink) + mirrors).filter { it.isNotBlank() }.joinToString("|")

                    // AGGIUNTO: questo assegna il poster della serie ad ogni episodio
                    episodesList.add(newEpisode(allLinks) {
                        this.name = a.text().trim()
                        this.season = seasonNum
                        this.episode = a.attr("data-num").filter { it.isDigit() }.toIntOrNull()
                        this.posterUrl = poster 
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
        data.split("|").forEach { link ->
            val cleanLink = fixUrl(link)
            if (cleanLink.startsWith("http") && !cleanLink.contains("cineblog001")) {
                loadExtractor(cleanLink, mainUrl, subtitleCallback, callback)
            } else if (cleanLink.isNotBlank()) {
                val doc = app.get(cleanLink).document
                doc.select("iframe[src], a[data-link], li[data-link]").forEach { el ->
                    val extracted = el.attr("data-link").ifEmpty { el.attr("src") }
                    if (extracted.startsWith("http") && !extracted.contains("mostraguarda")) {
                        loadExtractor(fixUrl(extracted), mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }
}
