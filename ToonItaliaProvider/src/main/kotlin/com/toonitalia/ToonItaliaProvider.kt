package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "Toon Italia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    // Basato sull'HTML che hai inviato: <article class="...">
    override suspend fun search(query: String): List<SearchResponse> {
        // La query viene passata nell'URL di ricerca di WordPress
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        // Selettore specifico per i risultati della ricerca su ToonItalia
        return document.select("article").mapNotNull { article ->
            // Estrae il link e il titolo dal tag h2 con classe 'entry-title'
            val titleHeader = article.selectFirst("h2.entry-title a")
            val title = titleHeader?.text() ?: return@mapNotNull null
            val href = titleHeader.attr("href")
            
            // Estrae l'immagine (poster)
            val posterUrl = article.selectFirst("img")?.attr("src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Selettori basati sulla struttura standard di ToonItalia
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.entry-content img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p")?.text()

        val episodes = mutableListOf<Episode>()
        
        // Cerca link che contengono "Episodio" o "Streaming" nel testo
        document.select("div.entry-content a").forEach { link ->
            val text = link.text()
            if (text.contains("Episodio", ignoreCase = true) || 
                text.contains("Streaming", ignoreCase = true) ||
                text.contains("Download", ignoreCase = true)) {
                
                episodes.add(Episode(
                    data = link.attr("href"),
                    name = text.trim()
                ))
            }
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Se l'URL dell'episodio porta a una pagina intermedia, la carichiamo
        val document = app.get(data).document

        // Cerca i player comuni (Voe, Speedvideo, Mixdrop ecc) negli iframe
        document.select("iframe").map { it.attr("src") }.forEach { iframeUrl ->
            // Il sistema "loadExtractor" di CloudStream riconosce automaticamente il sito (es. Voe)
            // e recupera il file video MP4 finale
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
