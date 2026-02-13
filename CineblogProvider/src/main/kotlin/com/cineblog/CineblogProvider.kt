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

        val title = this.selectFirst("h2, h3, .m-title")?.text() ?: a.attr("title").ifEmpty { a.text() }
        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
        
        // Determina se è una serie TV dal titolo o dall'URL
        val isTv = href.contains("/serie-tv/") || 
                   title.contains("serie tv", true) || 
                   title.contains("stagion", true)

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            doc.selectFirst("img._player-cover")?.attr("src") 
            ?: doc.selectFirst(".story-poster img, .m-img img, img[itemprop='image']")?.attr("src")
        )
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")
        
        // Controllo se ci sono elementi tipici delle serie TV (classi tt_series o tt_season)
        val isSerieContainer = doc.select(".tt_series, .tt_season, .tab-content, #tv_tabs").isNotEmpty()
        val isSerieUrl = url.contains("/serie-tv/")
        
        return if (isSerieUrl || isSerieContainer) {
            val episodesList = mutableListOf<Episode>()
            
            // 1. Cerchiamo i tab delle stagioni (div con classe tab-pane)
            val seasonTabs = doc.select(".tab-content .tab-pane")
            
            // 2. Filtriamo per assicurarci di prendere solo i tab degli episodi (devono avere un ID con "season")
            val validSeasonTabs = seasonTabs.filter { it.id().contains("season") || it.select("li a").isNotEmpty() }

            if (validSeasonTabs.isNotEmpty()) {
                validSeasonTabs.forEachIndexed { index, seasonElement ->
                    val seasonNum = index + 1
                    
                    // Cerchiamo ogni link di episodio all'interno del tab della stagione
                    seasonElement.select("li").forEach { li ->
                        val a = li.selectFirst("a") ?: return@forEach
                        val epData = a.attr("data-link").ifEmpty { a.attr("href") }
                        
                        // Estraiamo il numero episodio (es: data-num="1x5" o testo "1.5")
                        val dataNum = a.attr("data-num")
                        val epNum = if (dataNum.contains("x")) {
                            dataNum.substringAfter("x").toIntOrNull()
                        } else {
                            Regex("""\d+\.(\d+)""").find(li.text())?.groupValues?.get(1)?.toIntOrNull()
                        } ?: 1

                        episodesList.add(newEpisode(fixUrl(epData)) {
                            this.name = "Episodio $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster // Attiva la griglia con miniature
                        })
                    }
                }
            } else {
                // Caso fallback: lista piatta senza tab
                doc.select(".tt_series li, .episodes-list li").forEachIndexed { index, li ->
                    val a = li.selectFirst("a") ?: return@forEachIndexed
                    val epData = a.attr("data-link").ifEmpty { a.attr("href") }
                    episodesList.add(newEpisode(fixUrl(epData)) {
                        this.name = a.text().trim()
                        this.season = 1
                        this.episode = index + 1
                        this.posterUrl = poster
                    })
                }
            }

            // Rimuoviamo duplicati e ordiniamo
            val finalEpisodes = episodesList.distinctBy { "${it.season}-${it.episode}" }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
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
        if (data.startsWith("http") && !data.contains("cineblog001") && !data.contains("mostraguarda")) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

        var doc = app.get(fixUrl(data)).document
        val realUrl = doc.selectFirst(".open-fake-url")?.attr("data-url")
            ?: doc.selectFirst("iframe[src*='mostraguarda']")?.attr("src")
            
        if (!realUrl.isNullOrBlank()) {
            doc = app.get(fixUrl(realUrl)).document
        }

        // Estrae tutti i link possibili dai mirror (Mixdrop, Supervideo, ecc)
        doc.select("li[data-link], a[data-link], a.mr, iframe#_player, iframe[src*='embed']").forEach { el ->
            val link = el.attr("data-link").ifEmpty { el.attr("src") }
            if (link.isNotBlank() && !link.contains("mostraguarda.stream") && !link.contains("facebook") && !link.contains("google")) {
                loadExtractor(fixUrl(link), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
