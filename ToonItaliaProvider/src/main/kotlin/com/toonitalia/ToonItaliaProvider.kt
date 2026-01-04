package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.nodes.Element

class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "Toon Italia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    // Header comuni per evitare l'errore 403 Forbidden
    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/anime" to "Anime",
        "$mainUrl/category/film-animazione/" to "Film Animazione",
        "$mainUrl/category/serie-tv/" to "Serie TV",
    )

    // Funzione fondamentale per tradurre i domini mascherati in domini riconosciuti dagli estrattori
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
                // Aggiungiamo gli header al poster per evitare il 403 nella home
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
                // FIX per le immagini della ricerca: Referer necessario
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
            
        val img = document.selectFirst("div.entry-content img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
                     
        val plot = document.select("div.entry-content p")
            .firstOrNull { it.text().length > 50 && !it.text().contains("VOE") }?.text()

        val episodes = mutableListOf<Episode>()

        // Selettore migliorato per trovare i link degli episodi nei vari formati
        document.select("div.entry-content p, div.entry-content ul li").forEach { element ->
            val text = element.text()
            
            // Caso 1: Formato StagionexEpisodio (es. 1x01)
            val matchSE = Regex("""(\d+)[×x](\d+)""").find(text)
            // Caso 2: Formato Elenco (es. 01 – Titolo)
            val matchSimple = Regex("""^(\d+)\s*–""").find(text)

            val s = matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val e = matchSE?.groupValues?.get(2)?.toIntOrNull() 
                    ?: matchSimple?.groupValues?.get(1)?.toIntOrNull()
            
            if (e != null) {
                element.select("a").forEach { a ->
                    val href = a.attr("href")
                    val hostName = a.text().trim()
                    
                    if (href.isNotEmpty() && !href.startsWith("#") && href.contains("http")) {
                        episodes.add(newEpisode(href) {
                            this.name = if (matchSE != null) "Episodio $e ($hostName)" else "$text ($hostName)"
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
        // Applichiamo il fix agli URL prima di caricarli
        val fixedUrl = fixHostUrl(data)
        
        // Carichiamo l'estrattore con l'URL corretto (es. voe.sx invece di chuckle-tube)
        return loadExtractor(fixedUrl, subtitleCallback, callback)
    }
}
