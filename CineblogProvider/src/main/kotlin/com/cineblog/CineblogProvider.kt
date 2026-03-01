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

        // 1. In Evidenza
        val featured = mainDoc.select(".promo-item, .m-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (featured.isNotEmpty()) homePageList.add(HomePageList("In Evidenza", featured))

        // 2. Ultimi Aggiunti
        val latest = mainDoc.select(".block-th").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (latest.isNotEmpty()) homePageList.add(HomePageList("Ultimi Aggiunti", latest))

        // 3. Serie TV
        try {
            val tvDoc = app.get("$mainUrl/serie-tv/").document
            val tvItems = tvDoc.select(".block-th, .movie-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (tvItems.isNotEmpty()) homePageList.add(HomePageList("Serie TV", tvItems))
        } catch (e: Exception) { }

        // 4. Animazione
        try {
            val animDoc = app.get("$mainUrl/film/?genere=2").document
            val animItems = animDoc.select(".block-th, .movie-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (animItems.isNotEmpty()) homePageList.add(HomePageList("Animazione", animItems))
        } catch (e: Exception) { }

        // 5. Azione
        try {
            val actionDoc = app.get("$mainUrl/film/?genere=1").document
            val actionItems = actionDoc.select(".block-th, .movie-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (actionItems.isNotEmpty()) homePageList.add(HomePageList("Azione", actionItems))
        } catch (e: Exception) { }

        return newHomePageResponse(homePageList, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        for (page in 1..5) { // Limitate a 3 pagine per performance
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

        var title = this.selectFirst(".block-th-haeding, h2, h3, .m-title")?.text() 
                    ?: a.attr("title").ifEmpty { a.text() }
        
        // Pulizia titolo da anni, scritte streaming e tag stagioni [cite: 8]
        title = title.replace(Regex("""(?i)(\[.*?\]|\d+x\d+|Stagion[ei]\s+\d+|streaming|\(\d{4}.*?\))"""), "")
                     .replace(Regex("""[\-\s,._/]+$"""), "").trim()

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: img?.attr("src"))
        
        // Determina se è Serie TV dal link o dalla presenza di indicatori di episodi [cite: 9, 10]
        val isTv = href.contains("/serie-tv/") || this.selectFirst(".se_num") != null || title.contains("serie tv", true)

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("h1")?.text() ?: return null
        val title = rawTitle.replace(Regex("""(?i)(\[.*?\]|\d+x\d+|Stagion[ei]\s+\d+|streaming|\(\d{4}.*?\))"""), "")
                            .replace(Regex("""[\-\s,._/]+$"""), "").trim()
        
        val posterElement = doc.selectFirst(".story-cover img, img[itemprop='image'], .story-poster img, .m-img img")
        val poster = fixUrlNull(posterElement?.attr("data-src")?.ifEmpty { posterElement.attr("src") } ?: posterElement?.attr("src"))

        // Pulizia Trama: rimuove il titolo ripetuto in grassetto e il link +Info [cite: 12]
        val storyEl = doc.selectFirst(".story.space-sm")
        val plot = if (storyEl != null) {
            val tempEl = storyEl.clone()
            tempEl.select("strong, a").remove()
            tempEl.text().trim()
        } else {
            doc.selectFirst("meta[name='description']")?.attr("content")
        }

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

                    // Estrazione pulita numero episodio senza numeri extra [cite: 20]
                    val dataNum = a.attr("data-num")
                    val epNum = if (dataNum.contains("x")) {
                        dataNum.substringAfter("x").filter { it.isDigit() }.toIntOrNull()
                    } else {
                        dataNum.filter { it.isDigit() }.toIntOrNull()
                    } ?: a.text().filter { it.isDigit() }.toIntOrNull() ?: 1

                    episodesList.add(newEpisode(allLinks) {
                        this.name = "Episodio $epNum" // Forza visualizzazione pulita [cite: 20]
                        this.season = seasonNum
                        this.episode = epNum
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
            
            // Supporto diretto per Dropload e altri host [cite: 25]
            if (cleanLink.startsWith("http") && !cleanLink.contains("cineblog001") && !cleanLink.contains("mostraguarda")) {
                try {
                    loadExtractor(cleanLink, mainUrl, subtitleCallback, callback)
                } catch (e: Exception) { }
            } else if (cleanLink.isNotBlank()) {
                try {
                    val doc = app.get(cleanLink).document
                    
                    // Cerca specificamente iframe video (fondamentale per Dropload) [cite: 26, 27]
                    doc.select("iframe").forEach { iframe ->
                        val src = iframe.attr("src")
                        if (src.contains("dropload") || src.contains("supervideo") || src.contains("mixdrop")) {
                            loadExtractor(fixUrl(src), mainUrl, subtitleCallback, callback)
                        }
                    }

                    // Cerca link mirror in attributi data-link [cite: 26]
                    doc.select("li[data-link], a[data-link], div[data-link]").forEach { el ->
                        val mirror = el.attr("data-link")
                        if (mirror.isNotBlank() && !mirror.contains("mostraguarda")) {
                            loadExtractor(fixUrl(mirror), mainUrl, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) { }
            }
        }
        return true
    }
}
