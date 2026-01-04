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

    // Regex per pulire i titoli e favorire il matching automatico con TMDB/IMDb
    private val titleCleaner = Regex("(?i)streaming|sub\\s?ita|serie tv|serie animata|tutte le stagioni|completa|raccolta|film|animata")

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
            val rawTitle = titleHeader.text()
            val cleanedTitle = rawTitle.replace(titleCleaner, "").trim()
            
            val href = titleHeader.attr("href")
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("src")

            newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) {
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
            val rawTitle = titleHeader.text()
            val cleanedTitle = rawTitle.replace(titleCleaner, "").trim()
            
            val href = titleHeader.attr("href")
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("src")

            newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        
        val rawTitle = document.selectFirst("h1.entry-title")?.text() ?: ""
        val title = rawTitle.replace(titleCleaner, "").trim()
            
        val img = document.selectFirst("div.entry-content img, .post-thumbnail img")
        val poster = img?.attr("data-src") ?: img?.attr("src")
                     
        val plot = document.select("div.entry-content p")
            .firstOrNull { it.text().length > 50 && !it.text().contains("VOE") }?.text()

        val episodes = mutableListOf<Episode>()
        val entryContent = document.selectFirst("div.entry-content")
        val htmlContent = entryContent?.html() ?: ""

        // 1. LOGICA SERIE TV (Cerca episodi numerati)
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
                epName = epName.split("VOE", "LuluStream", "–", "Openload", ignoreCase = true).first().trim()

                links.forEach { a ->
                    val href = a.attr("href")
                    if (href.isNotEmpty() && href.contains("http") && !href.contains("jpg|png|jpeg".toRegex())) {
                        episodes.add(newEpisode(href) {
                            this.name = "$epName (${a.text()})"
                            this.season = s
                            this.episode = e
                            this.posterUrl = fixUrlNull(poster)
                        })
                    }
                }
            }
        }

        // 2. LOGICA FILM / FALLBACK (Se non ci sono episodi numerati o è un film)
        if (episodes.isEmpty()) {
            entryContent?.select("a")?.forEach { a ->
                val href = a.attr("href")
                val linkText = a.text().trim()
                
                // Filtriamo i link che puntano agli host video conosciuti
                if (href.contains("http") && 
                    (linkText.contains("VOE", true) || 
                     linkText.contains("Lulu", true) || 
                     linkText.contains("Stream", true))) {
                    
                    episodes.add(newEpisode(href) {
                        this.name = "Film - $linkText"
                        this.posterUrl = fixUrlNull(poster)
                    })
                }
            }
        }

        // Determiniamo il tipo: se è nella categoria film o ha pochi "episodi", lo trattiamo come film
        val tvType = if (url.contains("film") || episodes.size <= 2) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, tvType, episodes.sortedBy { it.episode }) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = commonHeaders
            this.plot = plot
            this.tags = listOf("ToonItalia")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadExtractor(fixHostUrl(data), subtitleCallback, callback)
    }
}
