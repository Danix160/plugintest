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
        "mixdrop", "streamtape", "fastream", "filemoon", "wolfstream", "streamwish", "vidoza"
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
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        val items = document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("data-lazy-src") ?: img?.attr("src")

            newTvSeriesSearchResponse(titleHeader.text(), titleHeader.attr("href"), TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = commonHeaders
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document
        return document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            newTvSeriesSearchResponse(titleHeader.text(), titleHeader.attr("href"), TvType.TvSeries) {
                this.posterUrl = searchPlaceholderLogo
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        val title = document.selectFirst("h1.entry-title")?.text()?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
        
        val img = document.selectFirst("div.entry-content img, .post-thumbnail img")
        val poster = img?.attr("data-src") ?: img?.attr("data-lazy-src") ?: img?.attr("src") ?: searchPlaceholderLogo

        val entryContent = document.selectFirst("div.entry-content")
        val episodes = mutableListOf<Episode>()

        // Dividiamo il contenuto HTML in blocchi basandoci sui ritorni a capo
        val blocks = entryContent?.html()?.split(Regex("(?i)<br\\s*/?>|</p>|</div>|<li>|\\n")) ?: listOf()

        var currentSeason = 1
        var currentSeasonName = ""
        var autoEpCounter = 1

        blocks.forEach { blockHtml ->
            val doc = Jsoup.parseBodyFragment(blockHtml)
            val text = doc.text().trim()
            
            // Trova tutti i link video presenti in questa specifica riga
            val links = doc.select("a").filter { a ->
                val href = a.attr("href")
                supportedHosts.any { host -> href.contains(host) }
            }

            if (links.isEmpty()) {
                // Se la riga è un titolo (grassetto o header) e non ha link, identifichiamo una nuova stagione
                if (text.length in 3..70 && !text.contains(Regex("(?i)episodio|link|clicca|streaming"))) {
                    if (episodes.isNotEmpty()) {
                        currentSeason++
                        autoEpCounter = 1 // Reset counter per la nuova parte/stagione
                    }
                    currentSeasonName = text
                }
            } else {
                // Estraiamo il numero dell'episodio dal testo, se presente
                val epMatch = Regex("""\b(\d+)\b""").find(text)
                val e = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: autoEpCounter
                
                // Uniamo i link della stessa riga (es. Voe e Mixdrop dello stesso episodio)
                val dataUrls = links.map { it.attr("href") }.joinToString("###")
                
                var epDisplayName = text.split(Regex("(?i)VOE|Lulu|Vidhide|Mixdrop|Streamtape|Vidoza")).first()
                    .replace(Regex("""^[-–—\s\d]+|[-–—\s]+$"""), "").trim()

                if (epDisplayName.isEmpty() || epDisplayName.length < 2) {
                    epDisplayName = "Episodio $e"
                }

                episodes.add(newEpisode(dataUrls) {
                    this.name = if (currentSeasonName.isNotEmpty()) "[$currentSeasonName] $epDisplayName" else epDisplayName
                    this.season = currentSeason
                    this.episode = e
                    this.posterUrl = poster
                })
                
                autoEpCounter = e + 1
            }
        }

        val isMovie = url.contains("film") || (episodes.size == 1 && currentSeason == 1)
        val finalEpisodes = episodes.distinctBy { it.data + it.name }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, finalEpisodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = document.select("div.entry-content p").firstOrNull { it.text().length > 50 }?.text()
                this.posterHeaders = commonHeaders
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.plot = document.select("div.entry-content p").firstOrNull { it.text().length > 50 }?.text()
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
