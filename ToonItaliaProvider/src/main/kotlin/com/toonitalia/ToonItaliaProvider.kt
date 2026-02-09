package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

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

        // --- NUOVA LOGICA DI SUDDIVISIONE PER TITOLI (STAGIONI) ---
        var currentSeason = 0
        var autoEpCounter = 1
        var currentSeasonName = ""

        // Iteriamo su tutti i figli di entry-content (p, h3, div, etc.)
        entryContent?.children()?.forEach { element ->
            val text = element.text().trim()
            val hasLinks = element.select("a").any { a -> 
                supportedHosts.any { host -> a.attr("href").contains(host) }
            }

            // Se l'elemento è un titolo (h1-h6) o un testo in grassetto senza link, è una NUOVA STAGIONE
            val isHeader = element.tagName().startsWith("h") || 
                           (element.select("strong, b").isNotEmpty() && !hasLinks && text.length < 100)

            if (isHeader && text.isNotBlank()) {
                currentSeason++
                autoEpCounter = 1 // Resetta il numero episodio per la nuova stagione
                currentSeasonName = text
            }

            // Se non abbiamo ancora trovato un titolo ma ci sono episodi, partiamo dalla Stagione 1
            if (currentSeason == 0 && hasLinks) currentSeason = 1

            // Cerchiamo i link video in questo elemento o nei suoi figli
            val linksInElement = element.select("a").filter { a ->
                supportedHosts.any { host -> a.attr("href").contains(host) }
            }

            if (linksInElement.isNotEmpty()) {
                val matchSimple = Regex("""\b(\d+)\b""").find(text)
                val e = matchSimple?.groupValues?.get(1)?.toIntOrNull() ?: autoEpCounter
                
                val dataUrls = linksInElement.map { it.attr("href") }.joinToString("###")
                
                // Pulizia nome episodio
                var epDisplayName = text.split(Regex("(?i)VOE|Lulu|Vidhide|Mixdrop|Streamtape")).first().trim()
                if (epDisplayName.length < 3) epDisplayName = "Episodio $e"
                
                // Aggiungiamo il nome della stagione se disponibile
                val finalName = if (currentSeasonName.isNotBlank()) "[$currentSeasonName] $epDisplayName" else epDisplayName

                episodes.add(newEpisode(dataUrls) {
                    this.name = finalName
                    this.season = currentSeason
                    this.episode = e
                    this.posterUrl = poster
                })
                autoEpCounter = e + 1
            }
        }

        val finalEpisodes = episodes.distinctBy { it.data + it.name }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.posterHeaders = commonHeaders
            this.plot = document.select("div.entry-content p").firstOrNull { it.text().length > 50 }?.text()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        data.split("###").forEach { url ->
            loadExtractor(fixHostUrl(url), subtitleCallback, callback)
        }
        return true
    }
}
