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

    private val supportedHosts = listOf(
        "voe", "chuckle-tube", "luluvdo", "lulustream", "vidhide", 
        "mixdrop", "streamtape", "fastream", "filemoon", "wolfstream", "streamwish"
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
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } 
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() } 
                ?: img?.attr("src")

            newTvSeriesSearchResponse(titleHeader.text(), titleHeader.attr("href"), TvType.TvSeries) {
                this.posterUrl = posterUrl
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
            val href = titleHeader.attr("href")
            val title = titleHeader.text()
                .replace(Regex("(?i)streaming|sub\\s?ita|serie\\s?tv|film"), "")
                .trim()

            // Genera un'immagine poster tramite DuckDuckGo/Bing Proxy
            // Aggiungiamo "poster" alla query per ottenere locandine verticali
            val encodedTitle = title.replace(" ", "+")
            val externalPoster = "https://external-content.duckduckgo.com/iu/?u=https://tse1.mm.bing.net/th?q=$encodedTitle+poster+movie&w=300&h=450&c=7"

            newTvSeriesSearchResponse(titleHeader.text(), href, TvType.TvSeries) {
                this.posterUrl = externalPoster
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        val title = document.selectFirst("h1.entry-title")?.text()?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
        
        val img = document.selectFirst("div.entry-content img, .post-thumbnail img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } 
            ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("placeholder") }
            ?: searchPlaceholderLogo
                     
        val plot = document.select("div.entry-content p").firstOrNull { it.text().length > 50 && !it.text().contains("VOE") }?.text()

        val episodes = mutableListOf<Episode>()
        val entryContent = document.selectFirst("div.entry-content")
        val isMovieUrl = url.contains("film") || url.contains("film-animazione")
        
        val lines = entryContent?.html()?.split(Regex("<br\\s*/?>|</p>|</div>|<li>|\\n")) ?: listOf()
        var autoEpCounter = 1

        lines.forEach { line ->
            val docLine = Jsoup.parseBodyFragment(line)
            val text = docLine.text().trim()
            val isTrailerRow = text.contains(Regex("(?i)sigla|opening|intro|ending|trailer"))
            
            val validLinks = docLine.select("a").filter { a -> 
                val href = a.attr("href")
                val linkText = a.text().lowercase()
                href.startsWith("http") && 
                !href.contains("toonitalia.xyz") && 
                supportedHosts.any { host -> href.contains(host) || linkText.contains(host) }
            }

            if (validLinks.isNotEmpty()) {
                val matchSE = Regex("""(\d+)[×x](\d+)""").find(text)
                val matchSimple = Regex("""^(\d+)""").find(text)

                val s = if (isTrailerRow) 0 else if (isMovieUrl) null else (matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1)
                val e = if (isTrailerRow) 0 else if (isMovieUrl) null else (matchSE?.groupValues?.get(2)?.toIntOrNull() ?: matchSimple?.groupValues?.get(1)?.toIntOrNull() ?: autoEpCounter)

                val dataUrls = validLinks.map { it.attr("href") }.joinToString("###")
                
                var epName = text.replace(Regex("""^\d+[×x]\d+|^\d+"""), "").replace("–", "").trim()
                epName = epName.split(Regex("(?i)VOE|LuluStream|Lulu|Streaming|Vidhide|Mixdrop")).first().trim()
                
                if (epName.isEmpty() || epName.length < 2) {
                    epName = when {
                        isTrailerRow -> "✨ Sigla / Opening"
                        isMovieUrl -> "Film"
                        else -> "Episodio $e"
                    }
                }

                episodes.add(newEpisode(dataUrls) {
                    this.name = epName
                    this.season = s
                    this.episode = e
                    this.posterUrl = poster
                })

                if (!isMovieUrl && !isTrailerRow) {
                    if (matchSE == null && matchSimple == null) autoEpCounter++ 
                    else if (e != null && e >= autoEpCounter) autoEpCounter = e + 1
                }
            }
        }

        val tvType = if (isMovieUrl) TvType.Movie else TvType.TvSeries
        val finalEpisodes = episodes.distinctBy { it.name + it.episode.toString() + it.season.toString() }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, tvType, finalEpisodes) {
            this.posterUrl = poster
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
        val urls = data.split("###")
        urls.forEach { url ->
            loadExtractor(fixHostUrl(url), subtitleCallback, callback)
        }
        return true
    }
}
