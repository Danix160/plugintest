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
        "$mainUrl/category/horror/" to "Horror",
        "$mainUrl/category/fantascienza/" to "Fantascienza"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        val home = document.select("li.movies").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        
        var posterUrl = selectFirst("img")?.attr("src")
        if (posterUrl?.startsWith("//") == true) {
            posterUrl = "https:$posterUrl"
        }
        
        val year = selectFirst(".year")?.text()?.trim()?.toIntOrNull()

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
        var poster = document.selectFirst(".post-thumbnail img")?.attr("src")
        if (poster?.startsWith("//") == true) poster = "https:$poster"
        
        val plot = document.selectFirst(".description p")?.text()
        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

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
        val html = response.text

        // 1. GESTIONE PLAYER NATIVO (LOADM / PANCAST)
        document.select("iframe").forEach { iframe ->
            // Cerchiamo src o data-src per il lazy loading
            var src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.startsWith("//")) src = "https:$src"

            if (src.contains("loadm.cam") || src.contains("pancast.net")) {
                try {
                    val frameRes = app.get(src, referer = data).text
                    // Regex per catturare il link master.m3u8 all'interno del sorgente del frame
                    val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(frameRes)
                    val finalUrl = m3u8Regex?.groupValues?.get(1)

                    if (finalUrl != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = "GuardaPlay",
                                name = "Player Alta Qualità",
                                url = finalUrl.replace("\\/", "/"),
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://loadm.cam/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                } catch (e: Exception) { }
            } else if (src.isNotEmpty() && !src.contains("google") && !src.contains("youtube")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // 2. RICERCA AUTOMATICA ESTRATTORI (Vidhide, Voe, Mixdrop, ecc.)
        // Questa regex analizza tutto l'HTML alla ricerca di link di video hoster conosciuti
        val hosterRegex = Regex("""https?://(?:vidhide|voe|streamwish|mixdrop|filemoon|ryderjet|vood|embedwish|wolfstream|dood|streamtape)[^\s"'<>\\\/]+""")
        hosterRegex.findAll(html).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")
            loadExtractor(cleanUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
