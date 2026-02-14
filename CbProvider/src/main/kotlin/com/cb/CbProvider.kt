package com.cb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup

class CbProvider : MainAPI() {
    override var mainUrl = "https://cb01uno.one"
    override var name = "CB01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    // Aggiunto Maxstream alla lista
    private val supportedHosts = listOf(
        "voe", "mixdrop", "streamtape", "fastream", "filemoon", 
        "wolfstream", "streamwish", "userload", "maxstream", "luluvdo", "lulustream"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/serietv/" to "Ultime Serie TV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        
        val items = document.select("div.post-video, div.box-film").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a") ?: return@mapNotNull null
            val title = titleElement.text().replace("Streaming", "").trim()
            val href = titleElement.attr("href")
            val posterUrl = element.selectFirst("img")?.attr("data-src") 
                         ?: element.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
        
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document
        
        return document.select("div.post-video, div.box-film").mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a") ?: return@mapNotNull null
            val title = titleElement.text()
            val href = titleElement.attr("href")
            val posterUrl = element.selectFirst("img")?.attr("data-src") 
                         ?: element.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("(?i)streaming|sub\\s?ita|\\bHD\\b"), "")?.trim() ?: ""
        
        val poster = document.selectFirst("div.entry-content img")?.attr("src")
                  ?: document.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val plot = document.selectFirst("div.entry-content p")?.text()
            ?.substringAfter("Trama:")?.trim()

        val year = document.selectFirst("a[href*='/anno/'], a[href*='/tag/anno-']")?.text()?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        // Controllo migliorato per identificare le serie TV
        val isSeries = url.contains("/serietv/") || 
                       document.selectFirst("h1.entry-title")?.text()?.contains(Regex("Stagione|Serie", RegexOption.IGNORE_CASE)) == true

        val entryContent = document.selectFirst("div.entry-content")
        
        if (!isSeries) {
            val videoLinks = entryContent?.select("a")?.filter { a ->
                val href = a.attr("href")
                supportedHosts.any { host -> href.contains(host, ignoreCase = true) }
            }?.joinToString("###") { it.attr("href") }

            if (!videoLinks.isNullOrBlank()) {
                episodes.add(newEpisode(videoLinks) {
                    this.name = "Film"
                })
            }
        } else {
            // Parsing righe per Serie TV
            val lines = entryContent?.html()?.split(Regex("<br\\s*/?>|</p>|<li>")) ?: listOf()
            var epCounter = 1
            
            lines.forEach { line ->
                val docLine = Jsoup.parseBodyFragment(line)
                val text = docLine.text()
                val links = docLine.select("a").filter { a ->
                    val href = a.attr("href")
                    supportedHosts.any { host -> href.contains(host, ignoreCase = true) }
                }

                if (links.isNotEmpty()) {
                    val matchSE = Regex("""(\d+)[×x](\d+)""").find(text)
                    val s = matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val e = matchSE?.groupValues?.get(2)?.toIntOrNull() ?: epCounter
                    
                    val dataUrls = links.joinToString("###") { it.attr("href") }
                    
                    episodes.add(newEpisode(dataUrls) {
                        this.name = text.split("-").firstOrNull()?.trim() ?: "Episodio $e"
                        this.season = s
                        this.episode = e
                    })
                    if (matchSE == null) epCounter++
                }
            }
        }

        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
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
        data.split("###").forEach { url ->
            // Cloudstream ha già un estrattore integrato per Maxstream
            loadExtractor(url, subtitleCallback, callback)
        }
        return true
    }
}
