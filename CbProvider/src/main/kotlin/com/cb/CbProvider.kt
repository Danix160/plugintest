package com.cb

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.json.JSONObject
import com.cb.extractors.MaxStreamExtractor // AGGIUNTO QUI: Importiamo l'estrattore

class CbProvider : MainAPI() {
    override var mainUrl = "https://cb01uno.one"
    override var name = "CB01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    private val supportedHosts = listOf(
        "voe", "mixdrop", "streamtape", "fastream", "filemoon", 
        "wolfstream", "streamwish", "maxstream", "lulustream", 
        "uprot", "stayonline", "swzz", "supervideo", "vidmoly"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Film Recenti",
        "$mainUrl/serietv/" to "Serie TV Recenti"
    )

    private fun fixTitle(title: String, isMovie: Boolean): String {
        return if (isMovie) {
            title.replace(Regex("(?i)streaming|\\[HD]|film gratis by cb01 official|\\(\\d{4}\\)"), "").trim()
        } else {
            title.replace(Regex("(?i)streaming|serie tv gratis by cb01 official|stagione \\d+|completa|[-–] ITA|[-–] HD"), "").trim()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        
        val items = document.select("div.card.mp-post, div.post-video, article.post").mapNotNull { element ->
            val titleElement = element.selectFirst(".card-title a, h3 a, h2 a") ?: return@mapNotNull null
            val href = titleElement.attr("href")
            if (href.contains("/tag/") || href.contains("/category/") || href.length < 10) return@mapNotNull null

            val isSeries = href.contains("/serietv/") || request.data.contains("serietv")
            val title = fixTitle(titleElement.text(), !isSeries)
            
            val posterUrl = element.selectFirst(".card-image img, img")?.let { img ->
                img.attr("data-lazy-src").ifBlank { 
                    img.attr("data-src").ifBlank { img.attr("src") } 
                }
            }

            newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document
        return document.select("div.card, div.post-video, article").mapNotNull { element ->
            val titleElement = element.selectFirst(".card-title a, h3 a, h2 a") ?: return@mapNotNull null
            val href = titleElement.attr("href")
            val isSeries = href.contains("/serietv/")
            val title = fixTitle(titleElement.text(), !isSeries)
            newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = element.selectFirst("img")?.attr("src")
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val isSeries = url.contains("/serietv/")
        
        val title = fixTitle(document.selectFirst("h1")?.text() ?: "", !isSeries)
        val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = document.select("div.ignore-css p").firstOrNull { it.text().length > 50 }?.text()
                  ?.substringBefore("+Info")?.trim()
        val year = Regex("\\d{4}").find(document.selectFirst("h1")?.text() ?: "")?.value?.toIntOrNull()

        val episodes = mutableListOf<Episode>()

        if (!isSeries) {
            val linkList = mutableSetOf<String>()
            
            document.select("div[data-src], div[data-link], li[data-src], iframe, table a, a.buttona_stream").forEach { el ->
                val link = el.attr("data-src").ifBlank { 
                    el.attr("data-link").ifBlank { 
                        el.attr("src").ifBlank { el.attr("href") } 
                    } 
                }
                if (link.contains("http") && (supportedHosts.any { link.contains(it) } || link.contains("stayonline"))) {
                    linkList.add(link)
                }
            }

            val finalLinks = linkList.filter { !it.contains("youtube") }
            if (finalLinks.isNotEmpty()) {
                episodes.add(newEpisode(finalLinks.joinToString("###")) { this.name = "Film - Streaming" })
            }
        } else {
            document.select("div.sp-wrap").forEachIndexed { index, wrap ->
                val seasonNum = index + 1
                wrap.select(".sp-body p, .sp-body li").forEach { row ->
                    val links = row.select("a").filter { a -> 
                        val h = a.attr("href")
                        supportedHosts.any { host -> h.contains(host) } || h.contains("stayonline")
                    }
                    if (links.isNotEmpty()) {
                        val text = row.text()
                        val epNum = Regex("(?i)episodio\\s?(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        episodes.add(newEpisode(links.joinToString("###") { it.attr("href") }) {
                            this.name = text.split("-").first().trim()
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            }
        }

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split("###").forEach { rawLink ->
            // AGGIUNTO QUI: Gestione speciale per MaxStream che usa il nuovo estrattore
            if (rawLink.contains("uprot.net") && rawLink.contains("msf")) {
                MaxStreamExtractor().getUrl(rawLink, mainUrl, subtitleCallback, callback)
            } 
            else {
                var finalUrl: String? = rawLink

                if (rawLink.contains("stayonline.pro")) {
                    finalUrl = bypassStayOnline(rawLink)
                } 
                else if (rawLink.contains("uprot.net") || rawLink.contains("swzz.xyz")) {
                    finalUrl = bypassShortener(rawLink)
                }

                finalUrl?.let { l ->
                    // Evitiamo di ricaricare uprot se è già stato gestito sopra
                    if (l.startsWith("http") && !l.contains("uprot.net")) {
                        loadExtractor(l, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }

    private suspend fun bypassStayOnline(link: String): String? {
        return try {
            val id = link.split("/").last { it.isNotBlank() }
            val response = app.post(
                "https://stayonline.pro/ajax/linkEmbedView.php",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to link),
                data = mapOf("id" to id)
            ).text
            JSONObject(response).getJSONObject("data").getString("value")
        } catch (e: Exception) { null }
    }

    private suspend fun bypassShortener(link: String): String? {
        return try {
            val req = app.get(link, headers = commonHeaders, allowRedirects = true)
            if (req.url != link && supportedHosts.any { req.url.contains(it) && !req.url.contains("uprot") }) {
                return req.url
            }

            val doc = req.document
            val scriptLinks = doc.select("script").map { it.data() }
                .flatMap { Regex("""https?://[^\s"']+""").findAll(it).map { m -> m.value }.toList() }
            
            val found = scriptLinks.firstOrNull { s -> 
                supportedHosts.any { s.contains(it) } && !s.contains("uprot") && !s.contains("swzz")
            }

            found ?: doc.select("a.btn, .download-link, #download a").firstOrNull { a ->
                val href = a.attr("href")
                supportedHosts.any { href.contains(it) }
            }?.attr("href") ?: req.url
            
        } catch (e: Exception) { null }
    }
}
