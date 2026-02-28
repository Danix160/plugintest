package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack
import org.jsoup.nodes.Element

// Estrattore dedicato per LoadM che eredita la logica AES da VidStack
open class LoadM : VidStack() {
    override var name = "LoadM"
    override var mainUrl = "https://loadm.cam"
}

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val hasMainPage = true
    override var lang = "it"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    // Configurazione della Home Page con le categorie corrette
    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/avventura/" to "Avventura",
        "$mainUrl/category/horror/" to "Horror",
    )

   override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        
        val items = document.select("li[id^=post-], article.post").mapNotNull { 
            it.toSearchResult() 
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Estraiamo il titolo, se manca saltiamo l'elemento
        val title = this.selectFirst(".entry-title")?.text() ?: return null
        // Estraiamo l'URL dal link .lnk-blk
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        // Poster con gestione fallback per URL che iniziano con //
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src") ?: "")
        val quality = this.selectFirst(".post-ql")?.text()
        
        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = posterUrl
            addQuality(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("li.post").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = fixUrl(document.selectFirst(".post-thumbnail img")?.attr("src") ?: "")
        val plot = document.selectFirst(".description p")?.text()
        
        // Pulizia anno: estraiamo solo i numeri per evitare errori di conversione
        val year = document.selectFirst(".year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val document = response.document

        // 1. Priorità all'estrattore LoadM (Vidstack)
        document.select("iframe[src*=loadm.cam]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                LoadM().getUrl(
                    url = src,
                    referer = src,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }

        // 2. Controllo altri iframe generici
        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src").ifEmpty { iframe.attr("data-src") })
            if (src.isNotEmpty() && !src.contains("loadm.cam") && !src.contains("google")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        return true
    }
}
