package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "Toon Italia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    // FIX 403: Forziamo un User-Agent comune per evitare i blocchi sui poster
    override val mainPage = mainPageOf(
        "$mainUrl/category/anime" to "Anime",
        "$mainUrl/category/film-animazione/" to "Film Animazione",
        "$mainUrl/category/serie-tv/" to "Serie TV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val title = titleHeader.text()
            val href = titleHeader.attr("href")
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val title = titleHeader.text()
            val href = titleHeader.attr("href")
            
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
            
        val img = document.selectFirst("div.entry-content img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
                     
        val plot = document.select("div.entry-content p")
            .firstOrNull { it.text().length > 50 }?.text()

        val episodes = mutableListOf<Episode>()

        document.select("div.entry-content p, div.entry-content ul li").forEach { element ->
            val text = element.text()
            val match = Regex("""(\d+)[×x](\d+)""").find(text)
            
            if (match != null) {
                val s = match.groupValues[1].toIntOrNull()
                val e = match.groupValues[2].toIntOrNull()
                
                element.select("a").forEach { a ->
                    val href = a.attr("href")
                    val hostName = a.text().trim()
                    
                    if (href.isNotEmpty() && !href.startsWith("#")) {
                        episodes.add(newEpisode(href) {
                            this.name = "Episodio $e ($hostName)"
                            this.season = s
                            this.episode = e
                        })
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Seguiamo il redirect per arrivare all'host finale (es. VOE o Lulu)
        val response = app.get(data)
        return loadExtractor(response.url, data, subtitleCallback, callback)
    }
}
