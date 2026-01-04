package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup

class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "ToonItalia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    private val searchPlaceholderLogo = "https://toonitalia.xyz/wp-content/uploads/2023/11/toonitalia-logo-1.png"

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
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

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
            
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = searchPlaceholderLogo
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
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } 
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("placeholder") }
            ?: searchPlaceholderLogo
                     
        val plot = document.select("div.entry-content p")
            .firstOrNull { it.text().length > 50 && !it.text().contains("VOE") }?.text()

        val episodes = mutableListOf<Episode>()
        val entryContent = document.selectFirst("div.entry-content")
        val htmlContent = entryContent?.html() ?: ""
        
        // Split più accurato per gestire meglio le righe degli episodi
        val lines = htmlContent.split(Regex("<br\\s*/?>|</p>|</div>|<li>")).filter { it.contains("href") }

        lines.forEach { line ->
            val docLine = Jsoup.parseBodyFragment(line)
            val text = docLine.text().trim()
            
            // Regex migliorata per catturare l'episodio senza saltare il primo
            val matchSE = Regex("""(\d+)[×x](\d+)""").find(text)
            val matchSimple = Regex("""^(\d+)""").find(text)

            val s = matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val e = matchSE?.groupValues?.get(2)?.toIntOrNull() ?: matchSimple?.groupValues?.get(1)?.toIntOrNull()

            val links = docLine.select("a")
            if (e != null && links.isNotEmpty()) {
                // Pulizia nome episodio
                var epName = text.replace(Regex("""^\d+[×x]\d+|^\d+"""), "").replace("–", "").trim()
                epName = epName.split("VOE", "LuluStream", ignoreCase = true).first().trim()
                if (epName.isEmpty()) epName = "Episodio $e"

                links.forEach { a ->
                    val href = a.attr("href")
                    if (href.isNotEmpty() && href.contains("http") && !href.contains("jpg|png|jpeg".toRegex())) {
                        episodes.add(newEpisode(href) {
                            this.name = "$epName (${a.text()})"
                            this.season = s
                            this.episode = e
                            this.posterUrl = poster
                        })
                    }
                }
            }
        }

        // FILM LOGIC (Fallback)
        if (episodes.isEmpty()) {
            entryContent?.select("a")?.forEach { a ->
                val href = a.attr("href")
                val linkText = a.text().trim()
                if (href.contains("http") && (linkText.contains("VOE", true) || linkText.contains("Lulu", true))) {
                    episodes.add(newEpisode(href) {
                        this.name = "Film - $linkText"
                        this.posterUrl = poster
                    })
                }
            }
        }

        val tvType = if (url.contains("film") || episodes.isEmpty()) TvType.Movie else TvType.TvSeries

        // Usiamo un distinctBy per evitare duplicati che sballano la numerazione dell'app
        val finalEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, tvType, finalEpisodes) {
            this.posterUrl = poster
            this.posterHeaders = commonHeaders
            this.plot = plot
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return loadExtractor(fixHostUrl(data), subtitleCallback, callback)
    }
}
