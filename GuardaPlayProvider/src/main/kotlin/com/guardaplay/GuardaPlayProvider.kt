package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/fantascienza/" to "Fantascienza",
        "$mainUrl/category/avventura/" to "Avventura"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val response = app.get(url, headers = headers)
        val document = response.document
        
        // Selettore specifico per la struttura post/article che hai inviato
        val items = document.select("li[id^=post-], article.post, .item").mapNotNull { 
            it.toSearchResult() 
        }
        
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // 1. URL: Cerchiamo la classe lnk-blk che hai indicato
        val href = this.selectFirst("a.lnk-blk")?.attr("href") 
            ?: this.selectFirst("a")?.attr("href") 
            ?: return null

        // 2. TITOLO: Dallo snippet si vede h2.entry-title
        val title = this.selectFirst("h2.entry-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.replace("Image ", "")
            ?: return null
        
        // 3. POSTER: Gestione TMDB //
        val img = this.selectFirst("img")
        val posterUrl = img?.let { 
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.startsWith("//")) "https:$src" else src
        }

        // 4. ANNO: Estratto dallo span.year
        val year = this.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = headers).document
        
        return document.select("article.post, .item").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        
        val title = document.selectFirst("h1.entry-title, h2.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".post-thumbnail img, .poster img")?.let { img ->
            val src = img.attr("src").ifEmpty { img.attr("data-src") }
            if (src.startsWith("//")) "https:$src" else src
        }
        val plot = document.selectFirst(".description p, .entry-content p")?.text()
        val year = document.selectFirst("span.year, .date")?.text()?.trim()?.let { 
            Regex("\\d{4}").find(it)?.value?.toIntOrNull() 
        }

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
        val html = response.text
        val document = response.document

        // Estrazione Iframe
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("youtube") && !src.contains("google")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // Link diretti
        val directVideoRegex = Regex("""https?://[^\s"'<>]+(?:\.txt|\.m3u8)""")
        directVideoRegex.findAll(html).forEach { match ->
            val videoUrl = match.value
            if (videoUrl.contains(Regex("master|playlist|index|cf-master"))) {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "GuardaPlay Direct",
                        videoUrl,
                        if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }

        // Scansione host comuni
        val hostRegex = Regex("""https?://[^\s"'<>]+""")
        hostRegex.findAll(html).forEach { match ->
            val foundUrl = match.value
            if (foundUrl.contains(Regex("vidhide|voe|streamwish|mixdrop|filemoon|ryderjet|hub|player"))) {
                loadExtractor(foundUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
