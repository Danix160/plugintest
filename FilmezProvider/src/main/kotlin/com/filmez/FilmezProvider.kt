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

    // User-Agent fisso per stabilizzare il bypass di Cloudflare
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private val cfInterceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/film/page/" to "Film Recenti",
        "$mainUrl/genere/animazione/page/" to "Animazione",
        "$mainUrl/genere/azione/page/" to "Azione",
        "$mainUrl/genere/fantascienza/page/" to "Fantascienza",
        "$mainUrl/genere/horror/page/" to "Horror",
        "$mainUrl/genere/commedia/page/" to "Commedia"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(url, interceptor = cfInterceptor, headers = mapOf("User-Agent" to userAgent)).document
        
        val home = document.select("article.masvideos-movie-grid-item, div.product, li.product").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Selettore universale per titoli e link (vodi/masvideos)
        val titleElement = this.selectFirst(".masvideos-loop-movie__title a, .product__title a, h2 a")
        val title = titleElement?.text() ?: return null
        val href = titleElement.attr("href") ?: return null
        
        // Gestione lazy loading per i poster
        val posterUrl = this.selectFirst("img")?.attr("data-src") 
                        ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type=movie"
        val document = app.get(url, interceptor = cfInterceptor, headers = mapOf("User-Agent" to userAgent)).document
        
        return document.select("article.masvideos-movie-grid-item, div.product, li.product").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfInterceptor, headers = mapOf("User-Agent" to userAgent)).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".masvideos-movie__poster img, .post-thumbnail img")?.attr("src")
        
        // Estrazione dettagli dal div.right-div (presente nel tuo filmes.txt)
        val rightDiv = document.selectFirst("div.right-div")
        val description = rightDiv?.selectFirst("h2 + p")?.text() 
                          ?: document.selectFirst(".masvideos-movie__short-description p, .entry-content p")?.text()
        
        val infoText = rightDiv?.text() ?: ""
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
        val document = app.get(data, interceptor = cfInterceptor, headers = mapOf("User-Agent" to userAgent)).document

        // 1. Estrazione link da bottoni/testo che puntano a uprot.net
        document.select("a[href*=uprot.net]").forEach { element ->
            val protectedUrl = element.attr("href")
            try {
                // Cloudstream segue i redirect automaticamente con allowRedirects = true
                val response = app.get(
                    protectedUrl, 
                    interceptor = cfInterceptor, 
                    headers = mapOf("User-Agent" to userAgent),
                    allowRedirects = true,
                    timeout = 15
                )
                // Carichiamo l'estrattore per l'URL finale (es. MixDrop, StreamWish, ecc.)
                loadExtractor(response.url, data, subtitleCallback, callback)
            } catch (e: Exception) {
                // Salta link non raggiungibili
            }
        }

        // 2. Estrazione da iframe (player incorporati)
        document.select(".masvideos-movie__player-embed iframe, .video-container iframe, iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("facebook.com") && !src.contains("twitter.com")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
