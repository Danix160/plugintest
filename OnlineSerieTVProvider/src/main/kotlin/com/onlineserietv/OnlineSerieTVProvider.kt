package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class OnlineSerieTVProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.online"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    // Header per simulare Googlebot e bypassare i controlli Cloudflare
    private val botHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film-streaming-ita-gratis/" to "Film",
        "$mainUrl/serie-tv-streaming-ita/" to "Serie TV",
        "$mainUrl/generi/animazione/" to "Animazione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val url = if (page <= 1) request.data else "${request.data}page/$page/"
    val res = app.get(url, headers = botHeaders)
    val document = res.document
    
    // Cambiato il selettore per trovare correttamente i contenitori
    val items = document.select("div.items .item, article.item").mapNotNull { element ->
        // Il titolo ora è direttamente in h3 a dentro l'elemento
        val titleElement = element.selectFirst("h3 a") ?: return@mapNotNull null
        val title = titleElement.text().trim()
        val href = titleElement.attr("href") ?: ""
        
        val img = element.selectFirst(".poster img")
        // Il sito ora usa spesso 'src' direttamente o 'data-src' per il lazy load
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } 
                     ?: img?.attr("src") 
                     ?: ""

        // Controllo migliorato per distinguere Serie TV da Film
        val isSeries = href.contains("/serietv/") || href.contains("/serie-tv/")

        newMovieSearchResponse(title, fixUrl(href), if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = fixUrl(poster)
        }
    }
    
    return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
}

    override suspend fun search(query: String): List<SearchResponse> {
    // Il nuovo URL di ricerca è strutturato diversamente nel nuovo dominio
    val url = "$mainUrl/search/$query/" 
    val res = app.get(url, headers = botHeaders)
    val document = res.document
    
    // Sostituito .result-item con .items .item
    return document.select(".items .item").mapNotNull { element ->
        val titleElement = element.selectFirst("h3 a") ?: return@mapNotNull null
        val title = titleElement.text().trim()
        val href = titleElement.attr("href") ?: ""
        
        val img = element.selectFirst(".poster img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } 
                     ?: img?.attr("src") 
                     ?: ""
        
        // Verifica del tipo basata sul link (più affidabile della vecchia label 'type')
        val isSeries = href.contains("/serietv/") || href.contains("/serie-tv/")

        newMovieSearchResponse(title, fixUrl(href), if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = fixUrl(poster)
        }
    }
}

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = botHeaders)
        val document = res.document
        
        val title = document.selectFirst(".data h1")?.text()?.trim() 
                    ?: document.selectFirst("h1")?.text()?.trim() 
                    ?: "Senza Titolo"
        
        val poster = document.selectFirst(".poster img")?.attr("src") ?: ""
        val plot = document.selectFirst(".wp-content p, .resumen")?.text()?.trim()
        
        val isSeries = url.contains("/serie-tv/") || document.selectFirst("#seasons") != null

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select(".episodios").forEach { season ->
                season.select("li").forEach { li ->
                    val a = li.selectFirst(".episodiotitle a") ?: return@forEach
                    val epHref = a.attr("href") ?: return@forEach
                    val epName = a.text().trim()
                    
                    episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = epName
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
            }
        } else {
            // Passiamo l'URL della pagina a loadLinks per cercare l'iframe lì
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
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
        // 1. Carichiamo la pagina passata (Film o Episodio) per trovare l'iframe del player
        val doc = app.get(data, headers = botHeaders).document
        val playerUrl = doc.selectFirst("iframe")?.attr("src") ?: return false
        
        // 2. Carichiamo la pagina del player (es. https://onlineserietv.com/video/...)
        // Spesso il vero video host è dentro un altro iframe qui.
        val playerDoc = app.get(fixUrl(playerUrl), headers = botHeaders).document
        val finalVideoUrl = playerDoc.selectFirst("iframe")?.attr("src") 
                           ?: playerDoc.selectFirst("a.btn")?.attr("href")

        // 3. Se abbiamo trovato un link (es. Mixdrop, DeltaBit), usiamo loadExtractor
        if (finalVideoUrl != null && finalVideoUrl.startsWith("http")) {
            // Nota: rimosso 'headers = botHeaders' perché non accettato da loadExtractor
            loadExtractor(finalVideoUrl, subtitleCallback, callback)
        }
        
        return true
    }
}
