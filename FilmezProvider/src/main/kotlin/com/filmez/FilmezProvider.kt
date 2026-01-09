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

    // 1. Aggiungiamo un User-Agent fisso per evitare che Cloudflare si insospettisca
    // cambiando browser tra la richiesta e la WebView
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val cfInterceptor = CloudflareKiller()

    // Configura i client appena il provider viene inizializzato
    init {
        app.defaultClient.interceptors.add(cfInterceptor)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 2. Usiamo l'URL corretto per la paginazione che avevamo individuato
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        
        // Aggiungiamo l'header User-Agent manualmente per sicurezza
        val document = app.get(url, interceptor = cfInterceptor, headers = mapOf("User-Agent" to userAgent)).document
        
        val home = document.select("article.masvideos-movie-grid-item, div.product").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    // ... (resto del codice per search e load)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type=movie"
        // Applichiamo lo stesso User-Agent anche qui
        val document = app.get(url, interceptor = cfInterceptor, headers = mapOf("User-Agent" to userAgent)).document
        return document.select("article.masvideos-movie-grid-item, div.product").mapNotNull {
            it.toSearchResult()
        }
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
