package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.APIHolder

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
            val posterUrl = article.selectFirst("img")?.attr("src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
    val document = app.get(url).document
    val title = document.selectFirst("h1.entry-title")?.text()?.replace("Streaming", "", true)?.trim() ?: ""
    val poster = document.selectFirst("div.entry-content img")?.attr("src")
    val plot = document.select("div.entry-content p").firstOrNull { it.text().length > 50 }?.text()

    val episodes = mutableListOf<Episode>()

    // Selezioniamo il paragrafo che contiene gli episodi
    // Cerchiamo quello che contiene il pattern tipico delle serie (numero x numero)
    document.select("div.entry-content p").forEach { p ->
        val htmlContent = p.html()
        // Dividiamo il contenuto per i tag <br>, così analizziamo riga per riga
        val lines = htmlContent.split("<br>")

        lines.forEach { line ->
            val lineDoc = org.jsoup.Jsoup.parseBodyFragment(line)
            val lineText = lineDoc.text()
            
            // Regex per trovare Stagione e Episodio (es: 1×01 o 2x14)
            val match = Regex("""(\d+)[×x](\d+)""").find(lineText)
            val season = match?.groupValues?.get(1)?.toIntOrNull()
            val episodeNumber = match?.groupValues?.get(2)?.toIntOrNull()
            
            // Estraiamo il titolo dell'episodio (testo tra il numero e il primo link)
            val episodeTitle = lineText.substringAfter("–").substringBefore("–").trim()

            lineDoc.select("a").forEach { a ->
                val href = a.attr("href")
                val hostName = a.text().trim()

                if (href.isNotBlank()) {
                    episodes.add(newEpisode(href) {
                        this.name = if (episodeTitle.isNotEmpty() && episodeTitle != hostName) {
                            "$episodeTitle ($hostName)"
                        } else {
                            "Episodio $episodeNumber ($hostName)"
                        }
                        this.season = season
                        this.episode = episodeNumber
                    })
                }
            }
        }
    }

    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
        this.posterUrl = poster
        this.plot = plot
    }
}

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // Carichiamo la pagina dell'host (es. VOE o LuluStream)
    val response = app.get(data)
    
    // Gli estrattori di Cloudstream spesso riconoscono automaticamente 
    // i link di VOE e LuluStream (LuluStream usa l'estrattore di MixDrop o simili)
    return loadExtractor(response.url, data, subtitleCallback, callback)
    }
}
