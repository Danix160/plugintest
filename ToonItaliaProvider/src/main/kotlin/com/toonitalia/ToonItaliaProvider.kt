package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchResponse
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
        "voe", "chuckle-tube", "luluvdo", "lulustream", "vidhide", "rpmshare",
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

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("(?i)streaming|sub\\s?ita|serie\\s?tv|film|animazione|ita"), "").trim()
    }

    // NUOVA LOGICA: Chiamata manuale a TMDB (Zero errori di compilazione)
    private suspend fun getPosterFromTMDB(title: String): String? {
        return try {
            val query = cleanTitle(title).replace(" ", "+")
            // Usiamo l'API pubblica di TMDB (API Key di default di Cloudstream o comune)
            val apiUrl = "https://api.themoviedb.org/3/search/multi?api_key=a9ddc3042079f82d09b68a6d9b4b09e2&query=$query&language=it-IT"
            val response = app.get(apiUrl).text
            
            // Estrazione manuale della stringa poster_path per evitare problemi con librerie JSON
            val posterPath = Regex("""\"poster_path\":\"\\/(.*?)\"""").find(response)?.groupValues?.get(1)
            if (posterPath != null) "https://image.tmdb.org/t/p/w500/$posterPath" else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = commonHeaders).document
        val items = document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val title = titleHeader.text()
            val href = titleHeader.attr("href")
            
            val poster = getPosterFromTMDB(title)

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster ?: searchPlaceholderLogo
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

            val poster = getPosterFromTMDB(title)

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster ?: searchPlaceholderLogo
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        val rawTitle = document.selectFirst("h1.entry-title")?.text() ?: ""
        val title = rawTitle.replace(Regex("(?i)streaming|sub\\s?ita"), "").trim()
        
        val poster = getPosterFromTMDB(title) ?: searchPlaceholderLogo

        val entryContent = document.selectFirst("div.entry-content")
        val fullText = entryContent?.text() ?: ""

        val plot = document.select("div.entry-content p")
            .map { it.text() }
            .firstOrNull { it.length > 60 && !it.contains(Regex("(?i)VOE|Lulu|Vidhide|Mixdrop|Streamtape")) }

        val yearMatch = Regex("""\b(19\d{2}|20[0-2]\d)\b""").find(fullText)?.groupValues?.get(1)?.toIntOrNull()
        val durationMatch = Regex("""(\d+)\s?min""").find(fullText)?.groupValues?.get(1)?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val isMovieUrl = url.contains("film") || url.contains("film-animazione")
        
        val lines = entryContent?.html()?.split(Regex("<br\\s*/?>|</p>|</div>|<li>|\\n")) ?: listOf()
        var absoluteEpCounter = 1

        lines.forEach { line ->
            val docLine = Jsoup.parseBodyFragment(line)
            val text = docLine.text().trim()
            
            val validLinks = docLine.select("a").filter { a -> 
                val href = a.attr("href")
                val linkText = a.text().lowercase()
                href.startsWith("http") && 
                !href.contains("toonitalia.xyz") && 
                supportedHosts.any { host -> href.contains(host) || linkText.contains(host) }
            }

            if (validLinks.isNotEmpty()) {
                val isTrailerRow = text.contains(Regex("(?i)sigla|intro|trailer"))
                val matchSE = Regex("""(\d+)[×x](\d+)""").find(text)

                val s = if (isTrailerRow) 0 else if (isMovieUrl) null else (matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1)
                val e = if (isTrailerRow) 0 else if (isMovieUrl) null else absoluteEpCounter

                val dataUrls = validLinks.map { it.attr("href") }.joinToString("###")
                var epName = text.split(Regex("(?i)VOE|LuluStream|Lulu|Streaming|Vidhide|Mixdrop|RPMShare")).first().trim()
                
                if (epName.isEmpty() || epName.length < 2) {
                    epName = if (isMovieUrl) "Film" else "Episodio $absoluteEpCounter"
                }

                episodes.add(newEpisode(dataUrls) {
                    this.name = epName
                    this.season = s
                    this.episode = e
                    this.posterUrl = poster
                })

                if (!isMovieUrl && !isTrailerRow) {
                    absoluteEpCounter++ 
                }
            }
        }

        val tvType = if (isMovieUrl) TvType.Movie else TvType.TvSeries
        val finalEpisodes = episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, finalEpisodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = yearMatch
                this.duration = durationMatch
                this.posterHeaders = commonHeaders
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, finalEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = yearMatch
                this.duration = durationMatch
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
        data.split("###").forEach { url ->
            loadExtractor(fixHostUrl(url), subtitleCallback, callback)
        }
        return true
    }
}
