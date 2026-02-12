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

    // Funzione aggiornata per includere le sezioni Film e Serie TV
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val home = mutableListOf<HomePageList>()
        
        // Definiamo le sezioni che vogliamo caricare
        val pages = listOf(
            Pair("$mainUrl/film/", "Ultimi Film"),
            Pair("$mainUrl/serie-tv/", "Ultime Serie TV"),
            Pair(mainUrl, "In Evidenza")
        )

        // Carichiamo le pagine in parallelo per velocità
        pages.apmap { (url, title) ->
            try {
                val doc = app.get(url).document
                val items = doc.select("div.promo-item, div.movie-item").mapNotNull {
                    it.toSearchResult()
                }
                if (items.isNotEmpty()) {
                    synchronized(home) {
                        home.add(HomePageList(title, items))
                    }
                }
            } catch (e: Exception) {
                // Se una pagina fallisce, continuiamo con le altre
            }
        }

        return newHomePageResponse(home, false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val title = a.attr("title").ifEmpty { a.text() } ?: return null
        val href = fixUrl(a.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))

        // Cerchiamo di capire se è una serie dal link o dal testo
        val type = if (href.contains("/serie-tv/") || title.contains("serie tv", true)) 
            TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        return document.select("div.promo-item, div.movie-item, div.result-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.film-poster img")?.attr("src"))
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
        
        val isSerie = url.contains("/serie-tv/") || document.select("#tv_tabs").isNotEmpty()

        return if (isSerie) {
            val episodesList = mutableListOf<Episode>()
            
            document.select("div.tt_series div.tab-pane ul li").forEach { li ->
                val a = li.selectFirst("a[id^=serie-]")
                if (a != null) {
                    val epData = a.attr("data-num")
                    val season = epData.split("x").firstOrNull()?.toIntOrNull()
                    val episode = epData.split("x").lastOrNull()?.toIntOrNull()
                    val epTitle = a.attr("data-title").substringBefore(":")
                    
                    val mainLink = a.attr("data-link")
                    val mirrors = li.select(".mirrors a.mr").map { it.attr("data-link") }.toMutableList()
                    mirrors.add(0, mainLink)

                    episodesList.add(newEpisode(mirrors.joinToString(",")) {
                        this.name = epTitle
                        this.season = season
                        this.episode = episode
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
        val urls = if (data.startsWith("http")) {
            if (data.contains(mainUrl)) {
                val doc = app.get(data).document
                doc.select("a[href*='mixdrop'], a[href*='supervideo'], a[href*='vidoza'], a[href*='streamtape']")
                    .map { it.attr("href") }
            } else {
                listOf(data)
            }
        } else {
            data.split(",")
        }

        urls.forEach { link ->
            val finalUrl = if (link.contains("/vai/")) {
                try { app.get(link).url } catch (e: Exception) { link }
            } else link

            // Chiamata standard dell'SDK
            loadExtractor(finalUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
