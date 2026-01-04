package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup

class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "Toon Italia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/anime" to "Anime",
        "$mainUrl/category/film-animazione/" to "Film Animazione",
        "$mainUrl/category/serie-tv/" to "Serie TV",
    )

    private fun fixHostUrl(url: String): String {
        return url
            .replace("chuckle-tube.com", "voe.sx")
            .replace("luluvdo.com", "lulustream.com")
            .replace("luluvideo.com", "lulustream.com")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = commonHeaders).document
        val items = document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val title = titleHeader.text()
            val href = titleHeader.attr("href")
            
            // Logica Fallback Immagine
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src") 
                ?: img?.attr("src") 
                ?: img?.attr("data-lazy-src")
                ?: "https://toonitalia.xyz/wp-content/uploads/2023/08/cropped-Majintoon-192x192.jpg" // Fallback icona sito

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
                this.posterHeaders = commonHeaders
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document

        return document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val title = titleHeader.text()
            val href = titleHeader.attr("href")
            
            // Nella ricerca le immagini spesso sono caricate in "lazy-load" o dentro i div post-thumbnail
            val img = article.selectFirst(".post-thumbnail img, .entry-content img, img")
            val posterUrl = img?.attr("data-src") 
                ?: img?.attr("data-lazy-src")
                ?: img?.attr("src")
                ?: "https://toonitalia.xyz/wp-content/uploads/2023/08/cropped-Majintoon-192x192.jpg"

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
            
        val img = document.selectFirst("div.entry-content img, .post-thumbnail img")
        val poster = img?.attr("data-src") ?: img?.attr("src") ?: img?.attr("data-lazy-src")
                     
        val plot = document.select("div.entry-content p")
            .firstOrNull { it.text().length > 50 && !it.text().contains("VOE") }?.text()

        val episodes = mutableListOf<Episode>()
        val entryContent = document.selectFirst("div.entry-content")

        val htmlContent = entryContent?.html() ?: ""
        val lines = htmlContent.split(Regex("<br\\s*/?>|</p>|</div>"))

        lines.forEach { line ->
            val docLine = Jsoup.parseBodyFragment(line)
            val text = docLine.text().trim()
            
            val matchSimple = Regex("""^(\d+)\s*–""").find(text)
            val matchSE = Regex("""(\d+)[×x](\d+)""").find(text)

            val e = matchSE?.groupValues?.get(2)?.toIntOrNull() ?: matchSimple?.groupValues?.get(1)?.toIntOrNull()
            val s = matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val links = docLine.select("a")
            if (e != null && links.isNotEmpty()) {
                var epName = text.split("–").getOrNull(1)?.trim() ?: "Episodio $e"
                epName = epName.split("VOE", "LuluStream", "–", ignoreCase = true).first().trim()

                links.forEach { a ->
                    val href = a.attr("href")
                    val hostName = a.text().trim()
                    
                    if (href.isNotEmpty() && !href.startsWith("#") && href.contains("http")) {
                        episodes.add(newEpisode(href) {
                            this.name = "$epName ($hostName)"
                            this.season = s
                            this.episode = e
                        })
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = commonHeaders
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedUrl = fixHostUrl(data)
        return loadExtractor(fixedUrl, subtitleCallback, callback)
    }
}
