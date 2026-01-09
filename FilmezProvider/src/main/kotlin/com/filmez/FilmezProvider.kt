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

    // User-Agent aggiornato a una versione Chrome molto recente
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    
    // Aumentiamo il timeout interno dell'intercettore
    private val cfInterceptor = CloudflareKiller()

    private fun getFixHeader(): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.8,en-US;q=0.5,en;q=0.3",
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/film/page/" to "Film Recenti",
        "$mainUrl/genere/horror/page/" to "Horror",
        "$mainUrl/genere/azione/page/" to "Azione",
        "$mainUrl/genere/fantascienza/page/" to "Fantascienza",
        "$mainUrl/genere/animazione/page/" to "Animazione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        // Applichiamo i fix headers per ogni chiamata
        val document = app.get(
            url, 
            interceptor = cfInterceptor, 
            headers = getFixHeader()
        ).document
        
        val home = document.select("article.masvideos-movie-grid-item, div.product, li.product").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".masvideos-loop-movie__title a, .product__title a, h2 a")
        val title = titleElement?.text()?.trim() ?: return null
        val href = titleElement.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type=movie"
        val document = app.get(url, interceptor = cfInterceptor, headers = getFixHeader()).document
        return document.select("article.masvideos-movie-grid-item, div.product, li.product").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfInterceptor, headers = getFixHeader()).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".masvideos-movie__poster img, .post-thumbnail img")?.attr("src")
        val description = document.selectFirst("div.right-div h2 + p, .masvideos-movie__short-description p, .entry-content p")?.text()
        val year = Regex("\\d{4}").find(document.selectFirst("div.right-div")?.text() ?: "")?.value?.toIntOrNull()

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
        val document = app.get(data, interceptor = cfInterceptor, headers = getFixHeader()).document

        // Caricamento dei link da uprot.net con gestione automatica degli estrattori
        document.select("a[href*=uprot.net]").forEach { element ->
            val link = element.attr("href")
            try {
                // Forziamo il caricamento tramite CloudflareKiller anche per i redirect
                val response = app.get(link, interceptor = cfInterceptor, headers = getFixHeader(), allowRedirects = true)
                loadExtractor(response.url, data, subtitleCallback, callback)
            } catch (e: Exception) { }
        }

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("facebook.com")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
