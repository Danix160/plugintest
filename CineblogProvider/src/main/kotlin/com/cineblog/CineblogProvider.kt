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
        val items = doc.select(".promo-item, .movie-item, .m-item, #dle-content > .col").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("In Evidenza", items)), false)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst(".movie-title a, .m-title a, h2 a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        
        if (href.contains("/tags/") || href.contains("/category/")) return null
        
        val imgElement = this.selectFirst("img")
        val poster = imgElement?.attr("data-src") ?: imgElement?.attr("src")
        val isSeries = this.selectFirst(".label.episode") != null || href.contains("/serie-tv/")
        
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(poster)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val doc = app.get(searchUrl).document
        return doc.select("#dle-content > .col, .movie-item, .m-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val content = doc.selectFirst("#movie-details, .movie-details, article") ?: return null
        
        val title = content.selectFirst("h1.movie_entry-title, h1")?.text() ?: "Sconosciuto"
        val poster = fixUrlNull(
            content.selectFirst("img._player-cover")?.attr("src") ?: 
            content.selectFirst("img")?.attr("data-src")
        )
        
        val plot = content.selectFirst("div.movie_entry-plot")?.text()
            ?.replace("...", "")?.removeSuffix("Leggi tutto")

        val rating = content.selectFirst("span.label.imdb")?.text()
        val details = content.select("div.movie_entry-details, .story-info")
        val genres = details.find { it.text().contains("Genere:") }?.select("a")?.map { it.text() }
        val year = details.find { it.text().contains("Anno:") }?.text()?.filter { it.isDigit() }

        return if (url.contains("/serie-tv/")) {
            // Qui andrebbe la logica getEpisodes, per ora usiamo una lista vuota
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year?.toIntOrNull()
            }
        } else {
            // Passiamo l'URL alla funzione loadLinks
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // Estrazione da Dropload e Supervideo tramite data-link
        doc.select("ul._player-mirrors li, div._hidden-mirrors li").forEach { li ->
            var link = li.attr("data-link")
            
            if (link.isNotBlank() && !link.contains("mostraguarda")) {
                // Fix per link che iniziano con // (es: //supervideo.cc/...)
                if (link.startsWith("//")) {
                    link = "https:$link"
                }
                
                // Carica automaticamente l'estrattore per Dropload o Supervideo
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
