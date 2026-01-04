package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document

class OnlineserieProvider : MainAPI() { // Nome classe corretto qui
    
    override var mainUrl = "https://onlineserietv.com"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    private val cfKiller = CloudflareKiller()
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0"

    override var mainPage = mainPageOf(
        mainUrl to "Ultime Serie TV",
        "$mainUrl/movies/" to "Ultimi Film"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}/page/$page/" else request.data
        val response = app.get(url, interceptor = cfKiller, headers = mapOf("User-Agent" to userAgent))
        val document = response.document
        
        val items = document.select("article.uagb-post__inner-wrap").mapNotNull { card ->
            val titleElement = card.selectFirst(".uagb-post__title a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            val poster = card.selectFirst(".uagb-post__image img")?.attr("src")
            val type = if (link.contains("/serietv/")) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, link, type) {
                addPoster(poster)
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }
    
        // Definiamo gli headers globali all'inizio della classe
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to mainUrl
    )

    // 1. Aggiungi questo all'inizio della classe per dare più tempo al sito di rispondere
override suspend fun search(query: String): List<SearchResponse> {
    val url = "$mainUrl/?s=${query.replace(" ", "+")}"
    
    // Aumentiamo il timeout perché Cloudflare richiede tempo per la sfida
    val response = app.get(
        url, 
        interceptor = cfKiller, 
        headers = commonHeaders,
        timeout = 120 // Aumentato a 120 secondi
    )
    
    val document = response.document
    
    // Il selettore potrebbe aver bisogno di una pulizia
    // Proviamo un selettore più generico per assicurarci di prendere i risultati
    val items = document.select("article, .uagb-post__inner-wrap, .result-item")
    
    return items.mapNotNull { card ->
        val titleElement = card.selectFirst("h2 a, h3 a, .uagb-post__title a") ?: return@mapNotNull null
        val title = titleElement.text().trim()
        val href = titleElement.attr("href")
        
        // Cerchiamo l'immagine in vari attributi (data-src è comune se c'è lazyload)
        val img = card.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.ifEmpty { null } 
                        ?: img?.attr("src")

        newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            // FONDAMENTALE: senza questo avrai sempre l'errore 403 nei log
            this.posterHeaders = commonHeaders 
        }
    }
}

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, interceptor = cfKiller)
        val document = response.document
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".uagb-post__image img")?.attr("src")
        val isMovie = !url.contains("/serietv/")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) { addPoster(poster) }
        } else {
            val episodes = document.select(".entry-content a[href*='/serietv/']").mapNotNull {
                newEpisode(it.attr("href")) { name = it.text().trim() }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { addPoster(poster) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, interceptor = cfKiller)
        val iframeSrc = response.document.select("iframe[src*='/streaming-']").attr("src")
        
        if (iframeSrc.isNotEmpty()) {
            val iframeRes = app.get(iframeSrc, referer = data, interceptor = cfKiller)
            iframeRes.document.select("a[href], iframe[src], button[data-link]").forEach { el ->
                var finalUrl = el.attr("src").ifEmpty { el.attr("href").ifEmpty { el.attr("data-link") } }
                if (finalUrl.isNotEmpty()) {
                    if (finalUrl.contains("/vai/")) finalUrl = bypassVai(finalUrl) ?: ""
                    if (finalUrl.isNotEmpty() && !finalUrl.contains("onlineserietv.com")) {
                        loadExtractor(finalUrl, iframeSrc, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }

    private suspend fun bypassVai(url: String): String? {
        val res = app.get(url, interceptor = cfKiller, allowRedirects = true)
        return if (res.url == url) null else res.url
    }
}
