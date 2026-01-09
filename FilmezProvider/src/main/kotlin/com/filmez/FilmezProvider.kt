package com.filmez

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

class FilmezProvider : MainAPI() {
    override var mainUrl = "https://filmez.org"
    override var name = "Filmez"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    private val cfInterceptor = CloudflareKiller()

    // Corretti i percorsi dei generi in base alla struttura standard del sito
    override val mainPage = mainPageOf(
        "$mainUrl/film/page/" to "Film Recenti",
        "$mainUrl/genere/animazione/page/" to "Animazione",
        "$mainUrl/genere/azione/page/" to "Azione",
        "$mainUrl/genere/fantascienza/page/" to "Fantascienza",
        "$mainUrl/genere/horror/page/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Correzione: assicuriamoci che l'URL sia composto bene (es: .../page/1)
        val url = "${request.data}$page/"
        val document = app.get(url, interceptor = cfInterceptor).document
        
        val home = document.select("article.masvideos-movie-grid-item, div.product").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Selettore più preciso basato sui file txt forniti
        val titleElement = this.selectFirst(".masvideos-loop-movie__title a, .product__title a")
        val title = titleElement?.text() ?: return null
        val href = titleElement.attr("href") ?: return null
        
        val posterUrl = this.selectFirst("img")?.attr("data-src") 
                        ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Il sito usa ?s= per la ricerca
        val url = "$mainUrl/?s=$query&post_type=movie"
        val document = app.get(url, interceptor = cfInterceptor).document
        return document.select("article.masvideos-movie-grid-item, div.product").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfInterceptor).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".masvideos-movie__poster img")?.attr("src")
        
        val rightDiv = document.selectFirst("div.right-div")
        val description = rightDiv?.selectFirst("h2 + p")?.text() 
                          ?: document.selectFirst(".masvideos-movie__short-description")?.text()
        
        val infoText = rightDiv?.select("b")?.text() ?: ""
        val year = Regex("\\d{4}").find(infoText)?.value?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = cfInterceptor).document

        // 1. Estrazione dai link diretti uprot.net
        document.select("div.right-div a[href*=uprot.net]").forEach { element ->
            val protectedUrl = element.attr("href")
            
            // Usiamo un try-catch per evitare che un link rotto blocchi gli altri
            try {
                val response = app.get(protectedUrl, interceptor = cfInterceptor, allowRedirects = true, timeout = 10)
                val finalUrl = response.url
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                // Log o ignora l'errore del singolo link
            }
        }

        // 2. Controllo per iframe masvideos
        document.select(".masvideos-movie__player-embed iframe, .video-container iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("facebook.com")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
