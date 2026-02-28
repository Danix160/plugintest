package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack
import org.jsoup.nodes.Element

// Estrattore dedicato per LoadM che eredita la logica AES da VidStack
class LoadM : VidStack() {
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

    // Configurazione della Home Page con le categorie richieste
    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/avventura/" to "Avventura",
        "$mainUrl/category/horror/" to "Horror",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        // Filtro anti-doppioni basato sull'URL univoco del film
        val home = document.select("li.post").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a.lnk-blk")?.attr("href") ?: return null)
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("src") ?: "")
        val quality = this.selectFirst(".post-ql")?.text()
        
        // Restituiamo solo Movie dato che non ci sono serie TV
        return newMovieSearchResponse(title, href, TvType.Movie) {
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

        // 1. Priorità a LoadM (Vidstack) - Gestione ID con cancelletto #
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

        // 2. Altri iframe (es. backup o altri server caricati dinamicamente)
        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src").ifEmpty { iframe.attr("data-src") })
            if (src.isNotEmpty() && !src.contains("loadm.cam") && !src.contains("google")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        return true
    }
}
