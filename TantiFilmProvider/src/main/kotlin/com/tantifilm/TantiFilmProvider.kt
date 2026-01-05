package com.tantifilm

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

class TantiFilmProvider : MainAPI() {
    override var mainUrl = "https://tanti-film.stream"
    override var name = "TantiFilm"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        // Selettore aggiornato basato sui log: cerca i contenitori comuni del tema Dooplay
        val items = document.select(".items article, .item, .poster").mapNotNull { element ->
            try {
                val a = element.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))
                
                // Evita di includere link alle categorie stesse
                if (href.removeSuffix("/").endsWith("/film") || href.removeSuffix("/").endsWith("/serie-tv")) 
                    return@mapNotNull null

                val title = element.selectFirst(".data h3 a, .title a, h3")?.text()?.trim() 
                    ?: element.selectFirst("img")?.attr("alt")
                    ?: a.attr("title")
                    ?: "Video"

                val img = element.selectFirst("img")
                val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } 
                    ?: img?.attr("src")

                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } catch (e: Exception) { null }
        }
        
        Log.d("TantiFilm", "Elementi trovati in Home: ${items.size}")
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.result-item, article, .item").mapNotNull { element ->
            try {
                val a = element.selectFirst("a") ?: return@mapNotNull null
                val title = element.selectFirst(".title a, h3, h2")?.text() ?: a.text()
                
                newMovieSearchResponse(title, fixUrl(a.attr("href")), TvType.Movie) {
                    val img = element.selectFirst("img")
                    this.posterUrl = img?.attr("src") ?: img?.attr("data-src")
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .data h1")?.text()?.trim() ?: "Senza Titolo"
        val isSeries = url.contains("/serie-tv/")
        
        val episodes = mutableListOf<Episode>()
        
        if (isSeries) {
            // Selettore per serie TV (struttura standard Dooplay)
            document.select("ul.episodios li, .episodio").forEach { ep ->
                val a = ep.selectFirst("a") ?: return@forEach
                val name = ep.selectFirst(".numerando")?.text() ?: a.text()
                episodes.add(newEpisode(fixUrl(a.attr("href"))) {
                    this.name = name.trim()
                })
            }
        } else {
            // Per i film cerchiamo il link del player o l'ID dell'opzione
            val link = document.selectFirst("iframe")?.attr("src") 
                ?: document.selectFirst("ul#playeroptionsul li")?.attr("data-url")
            
            if (link != null) {
                episodes.add(newEpisode(link) { this.name = "Film" })
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes)
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "")
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Se il dato è un URL diretto o un iframe, lo passiamo agli estrattori universali
        if (data.startsWith("http")) {
            loadExtractor(data, subtitleCallback, callback)
        }
        return true
    }
}
