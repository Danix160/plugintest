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
        "$mainUrl/serie-tv-generi/animazione/" to "Cartoon & Anime",
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

   override suspend fun search(query: String): List<SearchResponse> {
    // OnlineSerieTV usa sia ?s= che /search/
    // Proviamo la via più compatibile con gli headers corretti
    val url = "$mainUrl/?s=${query.replace(" ", "+")}"
    
    val response = app.get(
        url, 
        interceptor = cfKiller, 
        headers = mapOf("User-Agent" to userAgent)
    )
    
    val document = response.document
    
    // Il contenitore principale per i risultati in questo tema 
    // solitamente è 'article' o '.uagb-post__inner-wrap'
    val items = document.select("article, .uagb-post__inner-wrap, .post-item").mapNotNull { card ->
        // Cerchiamo il link del titolo
        val titleElement = card.selectFirst(".uagb-post__title a, h2 a, h3 a") ?: return@mapNotNull null
        val title = titleElement.text().trim()
        val link = titleElement.attr("href")
        
        // Cerchiamo il poster in modo più aggressivo
        val img = card.selectFirst("img")
        val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") }
            ?: img?.attr("data-lazy-src")
            ?: img?.attr("srcset")?.split(" ")?.firstOrNull() // Prende la prima immagine se c'è srcset

        val type = if (link.contains("/serietv/")) TvType.TvSeries else TvType.Movie

        newMovieSearchResponse(title, link, type) {
            this.posterUrl = poster
        }
    }
    
    return items
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
