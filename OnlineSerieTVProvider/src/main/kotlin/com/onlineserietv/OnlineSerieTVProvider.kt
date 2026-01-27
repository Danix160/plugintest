package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class OnlineSerieTVProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.online"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Cache-Control" to "max-age=0"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/serie-tv-generi/animazione/" to "Animazione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val res = app.get(url, headers = commonHeaders)
        val document = res.document
        
        val items = document.select("div.items article.item").mapNotNull { element ->
            val titleElement = element.selectFirst("h3 a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href") ?: ""
            
            val img = element.selectFirst(".poster img")
            val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } 
                         ?: img?.attr("src") 
                         ?: ""

            val isSeries = href.contains("/serie-tv/") || href.contains("/serietv/")

            newMovieSearchResponse(title, fixUrl(href), if (isSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query" 
        val res = app.get(url, headers = commonHeaders)
        val document = res.document
        
        return document.select("div.result-item, article.item").mapNotNull { element ->
            val titleElement = element.selectFirst(".title a, h3 a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href") ?: ""
            
            val img = element.selectFirst("img")
            val poster = img?.attr("data-src") ?: img?.attr("src") ?: ""
            
            val isSeries = href.contains("/serie-tv/") || href.contains("/serietv/")

            newMovieSearchResponse(title, fixUrl(href), if (isSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = commonHeaders)
        val document = res.document
        
        val title = document.selectFirst(".data h1")?.text()?.trim() 
                    ?: document.selectFirst("h1")?.text()?.trim() 
                    ?: "Senza Titolo"
        
        val poster = document.selectFirst(".poster img")?.attr("data-src") 
                     ?: document.selectFirst(".poster img")?.attr("src") ?: ""
        val plot = document.selectFirst(".wp-content p, .resumen")?.text()?.trim()
        
        val isSeries = url.contains("/serie-tv/") || document.selectFirst("#seasons") != null

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select(".episodios").forEach { season ->
                season.select("li").forEach { li ->
                    val a = li.selectFirst(".episodiotitle a") ?: return@forEach
                    val epHref = a.attr("href") ?: return@forEach
                    val epName = a.text().trim()
                    
                    // Estrae stagione ed episodio dall'interfaccia se possibile
                    val meta = li.selectFirst(".numerando")?.text() ?: "" // Esempio "1 - 1"
                    val s = meta.substringBefore("-").trim().toIntOrNull()
                    val e = meta.substringAfter("-").trim().toIntOrNull()

                    episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = epName
                        this.season = s
                        this.episode = e
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                addTrailer(document.selectFirst("iframe[src*='youtube']")?.attr("src"))
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = commonHeaders).document
        
        // Cerca tutti gli iframe perché a volte ce n'è più di uno (trailer + player)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("youtube").not()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }
        
        // Cerca anche link nei bottoni "Player"
        doc.select("li[id^='player-option']").forEach { option ->
            val type = option.attr("data-type")
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            
            // Alcuni siti caricano l'iframe via AJAX quando clicchi. 
            // Per ora proviamo la scansione degli iframe già presenti.
        }
        
        return true
    }
}
