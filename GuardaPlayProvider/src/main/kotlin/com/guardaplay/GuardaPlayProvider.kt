package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/commedia/" to "Commedia",
        "$mainUrl/category/dramma/" to "Dramma",
        "$mainUrl/category/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        // Selettore aggiornato basato su posterhome.txt
        val home = document.select("li.movies").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("//")) "https:$it" else it 
        }
        val year = selectFirst(".year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("li.movies").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")
        val plot = document.selectFirst(".description p")?.text()
        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()

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
        val document = app.get(data).document
        
        // 1. Cerca iframe (per LoadM o altri server)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("loadm.cam")) {
                val frameRes = app.get(src, referer = data).text
                // Estrae il file master.m3u8 dal sorgente del player
                val m3u8 = Regex("""src\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)""").find(frameRes)?.groupValues?.get(1)
                if (m3u8 != null) {
                    callback.invoke(
                        ExtractorLink(
                            "LoadM",
                            "LoadM",
                            m3u8,
                            referer = "https://loadm.cam/",
                            quality = Qualities.P1080.value,
                            isM3u8 = true
                        )
                    )
                }
            } else if (src.isNotEmpty()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // 2. Fallback: cerca link diretti nel JS della pagina
        val html = document.html()
        val regex = Regex("""https?://(?:vidhide|voe|streamwish|mixdrop|filemoon|ryderjet|vood)[^\s"'<>\\\/]+""")
        regex.findAll(html).forEach { match ->
            loadExtractor(match.value.replace("\\/", "/"), data, subtitleCallback, callback)
        }

        return true
    }
}
