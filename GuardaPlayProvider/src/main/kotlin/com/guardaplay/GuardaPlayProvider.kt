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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/genere/animazione/" to "Animazione",
        "$mainUrl/genere/azione/" to "Azione",
        "$mainUrl/genere/fantascienza/" to "Fantascienza",
        "$mainUrl/genere/avventura/" to "Avventura"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = headers).document
        
        val items = document.select("article.item").mapNotNull { 
            it.toSearchResult() 
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a, .title a")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src") 
            ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = headers).document
        
        return document.select("article.item").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".poster img")?.attr("src")
        val plot = document.selectFirst(".description p, .wp-content p")?.text()
        val year = document.selectFirst(".date")?.text()?.trim()?.toIntOrNull()

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
        val response = app.get(data, headers = headers)
        val document = response.document
        val html = response.text

        // 1. Cerca Iframe Standard (VidHide, Voe, ecc.)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("youtube") && !src.contains("google")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // 2. Cerca link diretti HLS (.txt o .m3u8) - FIX DEPRECATION QUI
        val directVideoRegex = Regex("""https?://[^\s"'<>]+(?:\.txt|\.m3u8)""")
        directVideoRegex.findAll(html).forEach { match ->
            val videoUrl = match.value
            if (videoUrl.contains(Regex("master|playlist|index|cf-master"))) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "GuardaPlay Direct",
                        url = videoUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true,
                        // Utilizziamo i parametri con nome per sicurezza con le nuove versioni
                    )
                )
            }
        }

        // 3. Scansione host supportati
        val hostRegex = Regex("""https?://[^\s"'<>]+""")
        hostRegex.findAll(html).forEach { match ->
            val foundUrl = match.value
            if (foundUrl.contains(Regex("vidhide|voe|streamwish|mixdrop|filemoon|ryderjet|hub"))) {
                loadExtractor(foundUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
