package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document

class OnlineSerieTV : MainAPI() {
    override var mainUrl = "https://onlineserietv.com"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    // Intercettore per gestire le sfide Cloudflare
    private val cfKiller = CloudflareKiller()
    
    // User Agent fisso per coerenza tra richieste e WebView
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0"

    override val mainPage = mainPageOf(
        mainUrl to "Ultime Serie TV",
        "$mainUrl/movies/" to "Ultimi Film"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}/page/$page/" else request.data
        val response = app.get(url, interceptor = cfKiller, headers = mapOf("User-Agent" to userAgent))
        val document = response.document
        
        // Selettore basato sull'HTML che hai fornito (UAGB Post Grid)
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
        val response = app.get("$mainUrl/?s=$query", interceptor = cfKiller)
        return response.document.select("article.uagb-post__inner-wrap").mapNotNull { card ->
            val titleElement = card.selectFirst(".uagb-post__title a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            val poster = card.selectFirst(".uagb-post__image img")?.attr("src")
            
            newMovieSearchResponse(title, link, TvType.TvSeries) {
                addPoster(poster)
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
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                addPoster(poster)
            }
        } else {
            // Estrazione episodi: cerca link che contengono "episodio" o si trovano nel contenuto principale
            val episodes = document.select(".entry-content a[href*='/serietv/']").mapNotNull {
                val href = it.attr("href")
                val epName = it.text().trim()
                if (href.isEmpty() || epName.isEmpty()) return@mapNotNull null
                
                newEpisode(href) {
                    this.name = epName
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                addPoster(poster)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Pagina dell'episodio/film
        val response = app.get(data, interceptor = cfKiller)
        
        // 2. Cerchiamo l'iframe di streaming (quello che hai postato tu)
        val iframeSrc = response.document.select("iframe[src*='/streaming-']").attr("src")
        
        if (iframeSrc.isNotEmpty()) {
            // 3. Entriamo nell'iframe (fondamentale passare il referer della pagina padre)
            val iframeRes = app.get(iframeSrc, referer = data, interceptor = cfKiller)
            val iframeDoc = iframeRes.document
            
            // 4. Cerchiamo link "vai" o iframe finali (MixDrop, Voe, etc.)
            iframeDoc.select("a[href], iframe[src], button[data-link]").forEach { el ->
                var finalUrl = el.attr("src").ifEmpty { 
                    el.attr("href").ifEmpty { el.attr("data-link") } 
                }

                if (finalUrl.isNotEmpty()) {
                    // Gestione bypass /vai/
                    if (finalUrl.contains("/vai/")) {
                        finalUrl = bypassVai(finalUrl) ?: ""
                    }
                    
                    // Carichiamo l'estrattore se il link è esterno
                    if (finalUrl.isNotEmpty() && !finalUrl.contains("onlineserietv.com")) {
                        loadExtractor(finalUrl, iframeSrc, subtitleCallback, callback)
                    }
                }
            }
        }
        
        return true
    }

    private suspend fun bypassVai(url: String): String? {
        return try {
            val res = app.get(url, interceptor = cfKiller, allowRedirects = true)
            val destination = res.okhttpResponse.request.url.toString()
            if (destination == url) null else destination
        } catch (e: Exception) {
            null
        }
    }
}
