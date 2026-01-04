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

    // Headers per evitare l'errore 403 (Forbidden) sulle immagini e richieste
    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/anime" to "Anime",
        "$mainUrl/category/film-animazione/" to "Film Animazione",
        "$mainUrl/category/serie-tv/" to "Serie TV",
    )

    // Converte i domini mascherati di ToonItalia in quelli reali per gli estrattori
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
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

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
            
        val img = document.selectFirst("div.entry-content img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
                     
        val plot = document.select("div.entry-content p")
            .firstOrNull { it.text().length > 50 && !it.text().contains("VOE") }?.text()

        val episodes = mutableListOf<Episode>()
        val entryContent = document.selectFirst("div.entry-content")

        // FIX: Dividiamo il contenuto HTML per riga (<br> o </p>) per separare gli episodi
        val htmlContent = entryContent?.html() ?: ""
        val lines = htmlContent.split(Regex("<br\\s*/?>|</p>|</div>"))

        lines.forEach { line ->
            val docLine = Jsoup.parseBodyFragment(line)
            val text = docLine.text().trim()
            
            // Regex per identificare il numero episodio (es: "01 –" o "1x01")
            val matchSimple = Regex("""^(\d+)\s*–""").find(text)
            val matchSE = Regex("""(\d+)[×x](\d+)""").find(text)

            val e = matchSE?.groupValues?.get(2)?.toIntOrNull() ?: matchSimple?.groupValues?.get(1)?.toIntOrNull()
            val s = matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1

            if (e != null) {
                // Pulizia del titolo episodio: togliamo il numero e i nomi dei server
                var epName = text.split("–").getOrNull(1)?.trim() ?: "Episodio $e"
                // Rimuoviamo eventuali residui di nomi server dal titolo
                epName = epName.split("VOE", "LuluStream", "–", ignoreCase = true).first().trim()

                docLine.select("a").forEach { a ->
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
        // Applichiamo il fix all'URL (chuckle-tube -> voe) prima di darlo a Cloudstream
        val fixedUrl = fixHostUrl(data)
        
        // Carichiamo l'estrattore. Cloudstream proverà a risolvere il link rimpiazzato.
        return loadExtractor(fixedUrl, subtitleCallback, callback)
    }
}
