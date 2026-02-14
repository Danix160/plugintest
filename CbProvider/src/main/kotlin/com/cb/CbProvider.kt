package com.cb

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup
import org.json.JSONObject

class CbProvider : MainAPI() {
    override var mainUrl = "https://cb01uno.one"
    override var name = "CB01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    )

    private val supportedHosts = listOf(
        "voe", "mixdrop", "streamtape", "fastream", "filemoon", 
        "wolfstream", "streamwish", "maxstream", "lulustream", "uprot", "stayonline"
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
        
        // Debug Log
        Log.d("CB01", "Chiamata URL: $url")
        
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        
        // Selettore espanso: cerca sia div.post-video che article.post
        val items = document.select("div.post-video, article.post, .box-film").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a, h3 a, .title a") ?: return@mapNotNull null
            val href = titleElement.attr("href")
            
            // Logica per determinare se è una serie TV dall'URL o dal titolo
            val isSeries = href.contains("/serietv/") || request.data.contains("serietv")
            val title = fixTitle(titleElement.text(), !isSeries)
            
            // Recupero poster migliorato (gestisce lazy-load di LiteSpeed)
            val posterUrl = element.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { 
                    img.attr("data-lazy-src").ifBlank { 
                        img.attr("src") 
                    } 
                }
            }

            newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }

        Log.d("CB01", "Elementi trovati: ${items.size}")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document
        
        return document.select("div.post-video, article.post").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a") ?: return@mapNotNull null
            val href = titleElement.attr("href")
            val isSeries = href.contains("/serietv/")
            val title = fixTitle(titleElement.text(), !isSeries)
            val posterUrl = element.selectFirst("img")?.attr("data-src") ?: element.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val isSeries = url.contains("/serietv/")
        
        val title = fixTitle(document.selectFirst("h1")?.text() ?: "", !isSeries)
        val poster = document.selectFirst("div.entry-content img, img.responsive-locandina")?.attr("src")
                  ?: document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        
        val plot = document.selectFirst("div.entry-content p")?.text()?.substringAfter("Trama:")?.trim()
        val year = document.selectFirst("a[href*='/anno/'], a[href*='/tag/anno-']")?.text()?.toIntOrNull()

        val episodes = mutableListOf<Episode>()

        if (!isSeries) {
            val videoLinks = document.select("div.entry-content a").filter { a ->
                val href = a.attr("href")
                supportedHosts.any { host -> href.contains(host, ignoreCase = true) }
            }.joinToString("###") { it.attr("href") }

            if (videoLinks.isNotBlank()) {
                episodes.add(newEpisode(videoLinks) { this.name = "Film" })
            }
        } else {
            // Parsing Serie TV (gestisce i toggle sp-wrap visti in serie.txt)
            document.select("div.sp-wrap").forEachIndexed { index, wrap ->
                val seasonName = wrap.selectFirst(".sp-head")?.text() ?: "Stagione ${index + 1}"
                val seasonNum = Regex("\\d+").find(seasonName)?.value?.toIntOrNull() ?: (index + 1)
                
                wrap.select(".sp-body p, .sp-body li").forEach { row ->
                    val links = row.select("a").filter { a -> 
                        supportedHosts.any { h -> a.attr("href").contains(h) } 
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
            var finalUrl: String? = rawLink

            if (rawLink.contains("stayonline.pro")) {
                finalUrl = bypassStayOnline(rawLink)
            } else if (rawLink.contains("uprot.net")) {
                finalUrl = bypassUprot(rawLink)
            }

            finalUrl?.let { l ->
                loadExtractor(l, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun bypassStayOnline(link: String): String? {
        return try {
            val id = link.split("/").filter { it.isNotBlank() }.last()
            val response = app.post(
                "https://stayonline.pro/ajax/linkEmbedView.php",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to link),
                data = mapOf("id" to id)
            ).text
            JSONObject(response).getJSONObject("data").getString("value")
        } catch (e: Exception) { null }
    }

    private suspend fun bypassUprot(link: String): String? {
        return try {
            val response = app.get(link, headers = commonHeaders).document
            response.selectFirst("a")?.attr("href")
        } catch (e: Exception) { null }
    }
}
