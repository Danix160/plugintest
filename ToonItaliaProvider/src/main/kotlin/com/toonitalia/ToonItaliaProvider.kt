package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "Toon Italia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val title = titleHeader.text()
            val href = titleHeader.attr("href")
            
            // Gestione Lazy Loading per i Poster
            val imgTag = article.selectFirst("img")
            val posterUrl = imgTag?.attr("data-src")?.takeIf { it.isNotBlank() } 
                            ?: imgTag?.attr("src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Pulizia titolo: rimuove "Streaming" e "Sub ITA" comuni su ToonItalia
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
            
        val imgTag = document.selectFirst("div.entry-content img")
        val poster = imgTag?.attr("data-src")?.takeIf { it.isNotBlank() } 
                     ?: imgTag?.attr("src")
                     
        val plot = document.select("div.entry-content p")
            .firstOrNull { it.text().length > 50 }?.text()

        val episodes = mutableListOf<Episode>()

        // Analizziamo il contenuto dell'articolo per trovare i link agli episodi
        document.select("div.entry-content p, div.entry-content ul li").forEach { element ->
            val text = element.text()
            
            // Regex migliorata per catturare Stagione x Episodio
            val match = Regex("""(\d+)[×x](\d+)""").find(text)
            
            if (match != null) {
                val s = match.groupValues[1].toIntOrNull()
                val e = match.groupValues[2].toIntOrNull()
                
                // Per ogni link (Host) presente in quel paragrafo/riga
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
        // Molti link su ToonItalia sono redirect. 
        // Usiamo app.get(data).url per seguire eventuali abbreviazioni/redirect
        val finalUrl = app.get(data).url
        
        return loadExtractor(finalUrl, data, subtitleCallback, callback)
    }
}
