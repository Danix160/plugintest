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

    // Intercettore per bypassare Cloudflare
    private val cfInterceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        mainUrl to "Film Recenti",
        "$mainUrl/?filter_genre=animazione" to "Animazione",
        "$mainUrl/?filter_genre=action" to "Azione",
        "$mainUrl/?filter_genre=fantascienza" to "Fantescienza",
        "$mainUrl/?filter_genre=horror" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Applichiamo l'intercettore Cloudflare alla richiesta
        val document = app.get(request.data + page, interceptor = cfInterceptor).document
        val home = document.select("article.masvideos-movie-grid-item, div.product").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".masvideos-loop-movie__title, .product__title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Gestione lazy loading per le immagini
        val posterUrl = this.selectFirst("img")?.attr("data-src") 
                        ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
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
        
        // Estrazione dettagli dal div.right-div (dall'HTML che hai fornito)
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

        // 1. Estrazione dai link diretti (uprot.net) presenti nel div.right-div
        document.select("div.right-div a[href*=uprot.net]").forEach { element ->
            val protectedUrl = element.attr("href")
            
            // Seguiamo il redirect di uprot.net per arrivare al video hoster finale
            val response = app.get(protectedUrl, interceptor = cfInterceptor, allowRedirects = true)
            val finalUrl = response.url

            // Carichiamo l'estrattore appropriato (MixDrop, MaxStream, ecc.)
            loadExtractor(finalUrl, data, subtitleCallback, callback)
        }

        // 2. Controllo secondario per iframe (se presenti)
        document.select(".masvideos-movie__player-embed iframe, iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("facebook.com")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
