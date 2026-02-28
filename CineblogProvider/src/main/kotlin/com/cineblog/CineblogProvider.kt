package com.cineblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.autos/"
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageList = mutableListOf<HomePageList>()
        
        // Carichiamo la Home principale per le prime due sezioni
        val mainDoc = app.get(mainUrl).document

        // 1. Sezione "In Evidenza" (Slider/Promo)
        val featured = mainDoc.select(".promo-item, .m-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url } [cite: 2]
        if (featured.isNotEmpty()) {
            homePageList.add(HomePageList("In Evidenza", featured))
        }

        // 2. Sezione "Ultimi Aggiunti" (Griglia block-th)
        val latest = mainDoc.select(".block-th").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
        if (latest.isNotEmpty()) {
            homePageList.add(HomePageList("Ultimi Aggiunti", latest))
        }

        // 3. Sezione "Animazione" (Genere specifico)
        try {
            val animationDoc = app.get("$mainUrl/film/?genere=2").document
            val animationItems = animationDoc.select(".block-th, .movie-item").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
            if (animationItems.isNotEmpty()) {
                homePageList.add(HomePageList("Animazione", animationItems))
            }
        } catch (e: Exception) {
            // Fallback silenzioso se la pagina genere non carica
        }

        return newHomePageResponse(homePageList, false) [cite: 2]
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        for (page in 1..5) { [cite: 2]
            try {
                val pagedResults = app.post(
                    "$mainUrl/index.php?do=search",
                    data = mapOf(
                        "do" to "search", [cite: 3]
                        "subaction" to "search", [cite: 4]
                        "search_start" to "$page",
                        "full_search" to "0",
                        "result_from" to "${(page - 1) * 20 + 1}", [cite: 5]
                        "story" to query
                    )
                ).document.select(".m-item, .movie-item, article, .block-th").mapNotNull {
                    it.toSearchResult()
                } [cite: 6]
                if (pagedResults.isEmpty()) break
                allResults.addAll(pagedResults)
            } catch (e: Exception) {
                break
            }
        }
        return allResults.distinctBy { it.url } [cite: 7]
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href")) [cite: 8]
        if (href.contains("/tags/") || href.contains("/category/")) return null

        // Supporto per titoli in diverse strutture (inclusa block-th-haeding)
        var title = this.selectFirst(".block-th-haeding, h2, h3, .m-title")?.text() 
                    ?: a.attr("title").ifEmpty { a.text() } [cite: 8]
        
        // Pulizia avanzata per titoli come "Heidi diventa principessa [ITA] [HD] (1977)"
        title = title.replace(Regex("""(?i)(\[.*?\]|\d+x\d+|Stagion[ei]\s+\d+|streaming|\(\d{4}\))"""), "")
                     .replace(Regex("""[\-\s,._/]+$"""), "").trim() [cite: 8]

        val img = this.selectFirst("img") [cite: 9]
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src")) [cite: 9]
        val isTv = href.contains("/serie-tv/") || title.contains("serie tv", true) [cite: 10]

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl } [cite: 10]
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl } [cite: 10]
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document [cite: 11]
        
        var title = doc.selectFirst("h1")?.text()?.trim() ?: return null [cite: 11]
        title = title.replace(Regex("""(?i)(\s+\d+x\d+.*|Stagion[ei]\s+\d+.*|streaming)"""), "")
                     .replace(Regex("""[\-\s,._/]+$"""), "").trim() [cite: 11]
        
        val poster = fixUrlNull(
            doc.selectFirst("img._player-cover")?.attr("src") 
            ?: doc.selectFirst(".story-poster img, .m-img img, img[itemprop='image']")?.attr("src")
        ) [cite: 12]

        var rawPlot = doc.selectFirst("meta[name='description']")?.attr("content") ?: "" [cite: 12]
        var cleanPlot = rawPlot.replace(Regex("""(?i).*?(?:streaming|treaming).*?(?:serie tv|film).*?(?:cb01|cineblog\d*01)\s*[,.:;\-–]*"""), "")
                               .replace(Regex("""(?i)cineblog\d*01\s*[,.:;\-–]*"""), "")
                               .replace(Regex("""(?i)cb01\s*[,.:;\-–]*"""), "") [cite: 13]
                               .replace(Regex("""^[\s,.:;–\-]+"""), "")
                               .trim()

        val half = cleanPlot.length / 2 [cite: 13]
        if (half > 15) { [cite: 14]
            val firstHalf = cleanPlot.substring(0, half).trim()
            val secondHalf = cleanPlot.substring(half).trim()
            if (secondHalf.contains(firstHalf) || firstHalf.contains(secondHalf)) { [cite: 15]
                cleanPlot = firstHalf
            }
        }

        val seasonContainer = doc.selectFirst(".tt_season") [cite: 15]
        
        return if (seasonContainer != null) {
            val episodesList = mutableListOf<Episode>()
            val seasonPanes = doc.select(".tt_series .tab-content .tab-pane") [cite: 16]
            
            seasonPanes.forEachIndexed { index, pane ->
                val seasonNum = index + 1
                pane.select("li").forEach { li ->
                    val a = li.selectFirst("a[id^=serie-]") ?: return@forEach [cite: 16]
                    
                    val mainLink = a.attr("data-link").ifEmpty { a.attr("href") } [cite: 17]
                    val mirrors = li.select(".mirrors a.mr").map { it.attr("data-link") } [cite: 17]
                    val allLinks = (listOf(mainLink) + mirrors).filter { it.isNotBlank() }.joinToString("|") [cite: 19]

                    val dataNum = a.attr("data-num")
                    val epNum = if (dataNum.contains("x")) {
                        dataNum.substringAfter("x").toIntOrNull()
                    } else { [cite: 20]
                        a.text().toIntOrNull()
                    } ?: 1

                    episodesList.add(newEpisode(allLinks) {
                        this.name = "Episodio $epNum"
                        this.season = seasonNum [cite: 21]
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList.distinctBy { "${it.season}-${it.episode}" }) {
                this.posterUrl = poster [cite: 22]
                this.plot = cleanPlot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) { [cite: 23]
                this.posterUrl = poster
                this.plot = cleanPlot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, [cite: 24]
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split("|") [cite: 24]

        links.forEach { link ->
            if (link.startsWith("http") && !link.contains("cineblog001") && !link.contains("mostraguarda")) {
                loadExtractor(link, link, subtitleCallback, callback) [cite: 25]
            } else {
                val doc = app.get(fixUrl(link)).document [cite: 25]
                val realUrl = doc.selectFirst(".open-fake-url")?.attr("data-url")
                    ?: doc.selectFirst("iframe[src*='mostraguarda']")?.attr("src") [cite: 25]
                    
                val targetDoc = if (!realUrl.isNullOrBlank()) app.get(fixUrl(realUrl)).document else doc [cite: 26]

                targetDoc.select("li[data-link], a[data-link], a.mr, iframe#_player, iframe[src*='embed']").forEach { el ->
                    val mirror = el.attr("data-link").ifEmpty { el.attr("src") }
                    if (mirror.isNotBlank() && !mirror.contains("mostraguarda.stream") && !mirror.contains("facebook") && !mirror.contains("google")) {
                        loadExtractor(fixUrl(mirror), link, subtitleCallback, callback) [cite: 27]
                    }
                }
            }
        }
        return true [cite: 27]
    }
}
