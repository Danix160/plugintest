package com.tantifilm

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class TantiFilmProvider : MainAPI() {
    override var mainUrl = "https://tanti-film.stream"
    override var name = "TantiFilm"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    // Header per simulare un browser vero e saltare blocchi base
    private val debugHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        
        // Carichiamo la pagina con gli headers del browser
        val res = app.get(url, headers = debugHeaders)
        val document = res.document
        
        Log.d("TantiFilm", "Download URL: $url")
        Log.d("TantiFilm", "Size HTML: ${res.text.length}")

        // Metodo di estrazione "Blind": cerchiamo tutti i link che sembrano contenuti
        val items = document.select("a").mapNotNull { a ->
            val href = a.attr("href") ?: ""
            
            // Filtriamo: deve contenere il percorso dei contenuti ma non essere la categoria
            val isMovie = href.contains("/film/") && !href.endsWith("/film/")
            val isShow = href.contains("/serie-tv/") && !href.endsWith("/serie-tv/")
            
            if (!isMovie && !isShow) return@mapNotNull null
            
            // Cerchiamo l'immagine nel link o nel genitore
            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt").ifBlank { a.text() }.ifBlank { "Video" }
            val poster = img.attr("data-src").ifBlank { img.attr("src") }

            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        }.distinctBy { it.url } // Rimuoviamo i duplicati

        Log.d("TantiFilm", "Risultati trovati (Metodo Blind): ${items.size}")
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = debugHeaders).document
        
        return document.select("div.result-item, .item, article").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".title, h3, h2")?.text() ?: a.text()
            
            newMovieSearchResponse(title, fixUrl(a.attr("href")), TvType.Movie) {
                val img = element.selectFirst("img")
                this.posterUrl = fixUrl(img?.attr("src") ?: img?.attr("data-src") ?: "")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = debugHeaders).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Senza Titolo"
        val isSeries = url.contains("/serie-tv/")
        
        val episodes = mutableListOf<Episode>()
        
        if (isSeries) {
            // Selettore episodi per temi Dooplay/TantiFilm
            document.select("ul.episodios li, .episodio, .les-content a").forEach { ep ->
                val a = if (ep.tagName() == "a") ep else ep.selectFirst("a")
                if (a != null) {
                    episodes.add(newEpisode(fixUrl(a.attr("href"))) {
                        this.name = a.text().trim()
                    })
                }
            }
        } else {
            // Cerchiamo iframe o bottoni player
            val link = document.selectFirst("iframe")?.attr("src") 
                ?: document.selectFirst(".dooplay_player_option")?.attr("data-url")
            
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
        if (data.startsWith("http")) {
            loadExtractor(data, subtitleCallback, callback)
        }
        return true
    }
}
