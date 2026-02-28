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

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
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
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        val seenUrls = mutableSetOf<String>()
        
        val items = document.select("li[id^=post-], article.post").mapNotNull { 
            val res = it.toSearchResult()
            if (res != null && seenUrls.add(res.url)) res else null
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h2.entry-title")?.text()?.trim() ?: this.selectFirst("img")?.attr("alt") ?: return null
        val img = this.selectFirst("img")
        val posterUrl = img?.let { 
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.startsWith("//")) "https:$src" else src
        }

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = commonHeaders).document
        val seenUrls = mutableSetOf<String>()
        return document.select("li[id^=post-], article.post").mapNotNull { 
            val res = it.toSearchResult()
            if (res != null && seenUrls.add(res.url)) res else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".post-thumbnail img, .poster img")?.attr("src")
        val plot = document.selectFirst(".description p, .entry-content p")?.text()
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
        val response = app.get(data, headers = commonHeaders)
        val document = response.document

        // 1. Cerchiamo l'iframe Trembed (che contiene il tasto Play "finto")
        val trembedUrl = document.selectFirst("iframe[src*='trembed=']")?.attr("src")
            ?: document.selectFirst("iframe[data-src*='trembed=']")?.attr("data-src")

        if (!trembedUrl.isNullOrEmpty()) {
            val fixedTrembed = if (trembedUrl.startsWith("//")) "https:$trembedUrl" else trembedUrl
            val trembedHtml = app.get(fixedTrembed, headers = commonHeaders).text

            // 2. Cerchiamo l'ID video (che di solito appare dopo il "clic")
            // Lo cerchiamo sia come ID numerico che come stringa LoadM
            val videoId = Regex("""(?:id|video_id)["']?\s*[:=]\s*["']([^"']+)""").find(trembedHtml)?.groupValues?.get(1)
                ?: Regex("""/e/([^"'?]+)""").find(trembedHtml)?.groupValues?.get(1)

            if (videoId != null) {
                // Simuliamo la chiamata API che il sito farebbe dopo il clic
                fetchLoadM(videoId, callback)
            }

            // 3. Cerchiamo altri link (Voe, Streamwish) dentro l'iframe trembed
            extractHosters(trembedHtml, subtitleCallback, callback)
        }

        // Fallback: cerca in tutta la pagina originale
        extractHosters(response.text, subtitleCallback, callback)

        return true
    }

    private suspend fun fetchLoadM(id: String, callback: (ExtractorLink) -> Unit) {
        // L'API di LoadM richiede spesso il referer del sito chiamante per sbloccarsi
        val apiUrl = "https://loadm.cam/api/v1/video?id=$id&r=guardaplay.space"
        try {
            val apiRes = app.get(apiUrl, headers = mapOf(
                "Referer" to "https://loadm.cam/",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to commonHeaders["User-Agent"]!!
            ))
            
            val finalUrl = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4)[^\s"'<>]*""").find(apiRes.text)?.value
            if (finalUrl != null) {
                callback.invoke(
                    newExtractorLink("LoadM", "LoadM", finalUrl, 
                        if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = "https://loadm.cam/"
                    }
                )
            }
        } catch (e: Exception) { }
    }

    private suspend fun extractHosters(html: String, subCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val hostRegex = Regex("""https?://(?:vidhide|voe|streamwish|mixdrop|filemoon|vood|maxstream|streamtape)[^\s"'<>\\\/]+""")
        hostRegex.findAll(html).forEach { match ->
            loadExtractor(match.value.replace("\\/", "/"), subCallback, callback)
        }
    }
}
