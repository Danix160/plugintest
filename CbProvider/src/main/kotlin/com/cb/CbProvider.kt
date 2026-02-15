package com.cb

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.json.JSONObject
import com.cb.extractors.MaxStreamExtractor

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
        "uprot", "stayonline", "swzz", "supervideo", "vidmoly", "maxsa"
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

    private fun parseElement(element: org.jsoup.nodes.Element, isTvSeriesSearch: Boolean = false): SearchResponse? {
        val titleElement = element.selectFirst("h2 a, h3 a, .card-title a, .post-title a, a[title]") ?: return null
        val href = titleElement.attr("href")
        if (href.contains("/tag/") || href.contains("/category/") || href.length < 15) return null
        
        val rawTitle = titleElement.text()
        val isSeries = isTvSeriesSearch || href.contains("/serietv/") || href.contains("/serie/") || 
                       rawTitle.contains(Regex("(?i)Stagion|Serie|Episodio"))

        val title = fixTitle(rawTitle, !isSeries)
        val posterUrl = element.selectFirst("img")?.let { img ->
            img.attr("data-lazy-src").ifBlank { 
                img.attr("data-src").ifBlank { img.attr("src") } 
            }
        }

        return newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        val items = document.select("div.card, div.post-video, article.post, div.mp-post").mapNotNull { 
            parseElement(it, request.data.contains("serietv")) 
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        val searchConfigs = listOf("$mainUrl/?s=$query" to false, "$mainUrl/serietv/?s=$query" to true)

        searchConfigs.forEach { (baseUrl, isTv) ->
            for (page in 1..10) { 
                val url = if (page == 1) baseUrl else {
                    if (baseUrl.contains("serietv")) "$mainUrl/serietv/page/$page/?s=$query"
                    else "$mainUrl/page/$page/?s=$query"
                }
                val response = try { app.get(url, headers = commonHeaders, timeout = 10L) } catch (e: Exception) { null }
                val document = response?.document ?: break
                val items = document.select("div.card, div.post-video, article, div.mp-post, div.post, li.item-list, .result-item")
                if (items.isEmpty()) break
                items.forEach { element -> parseElement(element, isTv)?.let { allResults.add(it) } }
                if (items.size < 5) break
            }
        }
        return allResults.distinctBy { it.url }.sortedByDescending { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val isSeries = url.contains("/serietv/") || url.contains("/serie/")
        
        val title = fixTitle(document.selectFirst("h1")?.text() ?: "", !isSeries)
        val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = document.select("div.ignore-css p, .entry-content p, .post-content p").firstOrNull { it.text().length > 50 }?.text()
                  ?.substringBefore("+Info")?.trim()
        val year = Regex("\\d{4}").find(document.selectFirst("h1")?.text() ?: "")?.value?.toIntOrNull()

        val episodes = mutableListOf<Episode>()

        if (!isSeries) {
            val linkList = mutableSetOf<String>()
            document.select("div[data-src], div[data-link], li[data-src], iframe, table a, a.buttona_stream, .stream-link").forEach { el ->
                val link = el.attr("data-src").ifBlank { 
                    el.attr("data-link").ifBlank { el.attr("src").ifBlank { el.attr("href") } } 
                }
                if (link.contains("http") && (supportedHosts.any { link.contains(it) } || link.contains("stayonline") || link.contains("uprot"))) {
                    linkList.add(link)
                }
            }
            val finalLinks = linkList.filter { !it.contains("youtube") }
            if (finalLinks.isNotEmpty()) episodes.add(newEpisode(finalLinks.joinToString("###")) { this.name = "Film - Streaming" })
        } else {
            document.select("div.sp-wrap, .entry-content table, .serie-tv-table, .sp-body").forEachIndexed { index, wrap ->
                val seasonNum = index + 1
                wrap.select("p, li, tr").forEach { row ->
                    val links = row.select("a").filter { a -> 
                        val h = a.attr("href")
                        supportedHosts.any { host -> h.contains(host) } || h.contains("stayonline") || h.contains("uprot")
                    }
                    if (links.isNotEmpty()) {
                        val text = row.text()
                        val epNum = Regex("(?i)episodio\\s?(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        episodes.add(newEpisode(links.joinToString("###") { it.attr("href") }) {
                            this.name = if (text.contains("-")) text.split("-").first().trim() else "Episodio $epNum"
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
            if (rawLink.contains("uprot.net") && rawLink.contains("msf")) {
                // Esecuzione estrattore con controllo esplicito del tipo di ritorno
                try {
                    MaxStreamExtractor().getUrl(rawLink, mainUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    // Se l'estrattore fallisce o lancia eccezione, fallback sul bypass generico
                    bypassShortener(rawLink)?.let { loadExtractor(it, subtitleCallback, callback) }
                }
            } else {
                val finalUrl = when {
                    rawLink.contains("stayonline.pro") -> bypassStayOnline(rawLink)
                    rawLink.contains("uprot.net") || rawLink.contains("swzz.xyz") || rawLink.contains("maxsa") -> bypassShortener(rawLink)
                    else -> rawLink
                }
                finalUrl?.let { loadExtractor(it, subtitleCallback, callback) }
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
            if (req.url != link && supportedHosts.any { req.url.contains(it) && !req.url.contains("uprot") }) return req.url

            val doc = req.document
            
            val foundLink = doc.select("a, iframe").mapNotNull { 
                val target = it.attr("href").ifBlank { it.attr("src") }
                if (supportedHosts.any { host -> target.contains(host) } && !target.contains("uprot")) target else null
            }.firstOrNull()
            
            if (foundLink != null) return foundLink

            val hostPattern = supportedHosts.filter { it != "uprot" }.joinToString("|")
            val regex = Regex("""https?://[\w\d\.]+\.(?:$hostPattern)[\w\d\.\-/=\?&%+]*""")
            val match = regex.find(doc.html())?.value
            
            match ?: req.url
        } catch (e: Exception) { null }
    }
}
