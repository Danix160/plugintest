package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller

class OnlineserieProvider : MainAPI() {

    override var mainUrl = "https://onlineserietv.com"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    // CloudflareKiller per gestire la protezione del sito
    private val cfKiller = CloudflareKiller()

    // Headers fondamentali per evitare i blocchi 403 sulle immagini e sulle pagine
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override var mainPage = mainPageOf(
        mainUrl to "Ultime Serie TV",
        "$mainUrl/movies/" to "Ultimi Film"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        
        // Timeout a 120s perché i log mostrano che Cloudflare Turnstile è lento (60s non bastano)
        val response = app.get(url, interceptor = cfKiller, headers = commonHeaders, timeout = 120)
        val document = response.document

        val items = document.select("article, .uagb-post__inner-wrap").mapNotNull { card ->
            val titleElement = card.selectFirst(".uagb-post__title a, h2 a, h3 a")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            
            val img = card.selectFirst("img")
            val poster = img?.attr("data-src")?.ifEmpty { null } ?: img?.attr("src")
            
            val type = if (link.contains("/serietv/")) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(title, link, type) {
                this.posterUrl = poster
                // FONDAMENTALE: Passiamo gli headers anche alle immagini per evitare il 403
                this.posterHeaders = commonHeaders
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val response = app.get(url, interceptor = cfKiller, headers = commonHeaders, timeout = 120)
        val document = response.document

        return document.select("article, .uagb-post__inner-wrap, .result-item").mapNotNull { card ->
            val titleElement = card.selectFirst("h2 a, h3 a, .uagb-post__title a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val href = titleElement.attr("href")
            
            val img = card.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.ifEmpty { null } ?: img?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, interceptor = cfKiller, headers = commonHeaders, timeout = 120)
        val document = response.document
        val title = document.selectFirst("h1.uagb-post__title, h1")?.text()?.trim() ?: ""
        
        val poster = document.selectFirst(".uagb-post__image img, .wp-block-image img, article img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        val isMovie = !url.contains("/serietv/")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = commonHeaders
            }
        } else {
            // Estrazione episodi migliorata
            val episodes = document.select(".entry-content a, .uagb-post__text a").mapNotNull {
                val href = it.attr("href") ?: ""
                val text = it.text().trim()
                // Evitiamo di aggiungere link non pertinenti o la pagina stessa
                if ((href.contains("/serietv/") && href != url) || href.contains("/vai/")) {
                    newEpisode(href) {
                        this.name = text
                    }
                } else null
            }.distinctBy { it.data }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Se l'URL è un redirect "/vai/", lo risolviamo
        val actualUrl = if (data.contains("/vai/")) bypassVai(data) ?: data else data
        
        val response = app.get(actualUrl, interceptor = cfKiller, headers = commonHeaders, timeout = 60)
        val document = response.document

        // Cerca iframe di streaming
        document.select("iframe[src*='streaming'], iframe[src*='player'], .entry-content iframe").forEach { iframe ->
            val src = iframe.attr("src")
            loadExtractor(src, actualUrl, subtitleCallback, callback)
        }

        // Cerca link diretti ai server comuni
        document.select(".entry-content a[href*='wish'], .entry-content a[href*='bit'], .entry-content a[href*='drop'], .entry-content a[href*='wolf']").forEach { a ->
            val href = a.attr("href")
            loadExtractor(href, actualUrl, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun bypassVai(url: String): String? {
        val res = app.get(url, interceptor = cfKiller, headers = commonHeaders, allowRedirects = true)
        return if (res.url == url) {
            res.document.selectFirst("a.btn-server, .entry-content a")?.attr("href")
        } else res.url
    }
}
