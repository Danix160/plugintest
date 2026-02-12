package com.cineblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.club"
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    // 1. HOME PAGE
    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()

        // Sezioni principali (Ultimi film, In evidenza)
        val items = document.select("div.promo-item").mapNotNull {
            it.toSearchResult()
        }
        if (items.isNotEmpty()) home.add(HomePageList("Ultimi Inserimenti", items))

        return newHomePageResponse(home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // 2. RICERCA
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        return document.select("div.promo-item, div.movie-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. CARICAMENTO DETTAGLI (FILM E SERIE TV)
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.film-poster img")?.attr("src"))
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
        
        val isSerie = document.select("#tv_tabs").isNotEmpty()

        return if (isSerie) {
            val episodesList = mutableListOf<Episode>()
            
            // Parsing stagioni ed episodi (basato su serie.txt)
            document.select("div.tt_series div.tab-pane ul li").forEach { li ->
                val a = li.selectFirst("a[id^=serie-]")
                if (a != null) {
                    val epData = a.attr("data-num") // Formato "1x1"
                    val season = epData.split("x").firstOrNull()?.toIntOrNull()
                    val episode = epData.split("x").lastOrNull()?.toIntOrNull()
                    val epTitle = a.attr("data-title").substringBefore(":")
                    
                    // Raccogliamo i mirror come dati dell'episodio
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

    // 4. ESTRAZIONE LINK VIDEO
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' contiene o l'URL del film o la lista di mirror dell'episodio separati da virgola
        val urls = if (data.contains(mainUrl)) {
            // È un film, cerchiamo i link nella pagina
            val doc = app.get(data).document
            doc.select("a[href*='mixdrop'], a[href*='supervideo'], a[href*='vidoza'], a[href*='streamtape']")
                .map { it.attr("href") }
        } else {
            // Sono i link dell'episodio salvati precedentemente
            data.split(",")
        }

        urls.forEach { link ->
            // Se il link è un redirect interno del sito (es. /vai/), dobbiamo risolverlo
            val finalUrl = if (link.contains("/vai/")) {
                app.get(link).url 
            } else link

            // Carica i link usando gli estrattori integrati di CloudStream
            loadFixedExtractor(finalUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
