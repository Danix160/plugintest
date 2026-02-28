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
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            // Fix per errore ENOENT nei log: aggiunge https se manca
            when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") && !src.startsWith("//") -> "https://image.tmdb.org$src" // TMDB fallback
                !src.startsWith("http") -> null
                else -> src
            }
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
        val year = document.selectFirst("span.year")?.text()?.trim()?.filter { it.isDigit() }?.toIntOrNull()

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
        val html = response.text
        val document = response.document

        // 1. Cerchiamo l'iframe del player (spesso identificato da 'trembed')
        val playerIframe = document.selectFirst("iframe[src*='trembed'], iframe[data-src*='trembed']")
        val playerUrl = playerIframe?.attr("src")?.takeIf { it.isNotEmpty() } ?: playerIframe?.attr("data-src")

        if (!playerUrl.isNullOrEmpty()) {
            val fixedIframeUrl = if (playerUrl.startsWith("//")) "https:$playerUrl" else playerUrl
            
            // Entriamo nell'iframe per trovare il video_id necessario per l'API
            val iframeRes = app.get(fixedIframeUrl, headers = commonHeaders.plus("Referer" to data))
            val iframeHtml = iframeRes.text

            // Estraiamo il video_id (quello che verrebbe usato dal pulsante "Play")
            val videoId = Regex("""video_id\s*[:=]\s*["']([^"']+)""").find(iframeHtml)?.groupValues?.get(1)
                ?: Regex("""id\s*[:=]\s*["']([^"']+)""").find(iframeHtml)?.groupValues?.get(1)
                ?: Regex("""/e/([^"'?]+)""").find(fixedIframeUrl)?.groupValues?.get(1)

            if (videoId != null) {
                // Chiamata all'API di LoadM simulando il clic
                val apiUrl = "https://loadm.cam/api/v1/video?id=$videoId&r=guardaplay.space"
                try {
                    val apiRes = app.get(apiUrl, headers = mapOf(
                        "Referer" to "https://loadm.cam/",
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to commonHeaders["User-Agent"]!!
                    )).text
                    
                    val finalUrl = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4)[^\s"'<>]*""").find(apiRes)?.value
                    if (finalUrl != null) {
                        callback.invoke(
                            newExtractorLink("LoadM", "LoadM High Speed", finalUrl.replace("\\/", "/"), 
                                if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = "https://loadm.cam/"
                            }
                        )
                    }
                } catch (e: Exception) { /* API fallita */ }
            }
        }

        // 2. Fallback: Estrazione automatica di altri host (Voe, Streamwish, ecc.)
        // Cerca i link sia nella pagina principale che dentro l'iframe se esiste
        val contentToSearch = html + (playerUrl?.let { "" } ?: "") 
        val hostRegex = Regex("""https?://(?:voe\.sx|streamwish\.[^\s"']+|vidhide\.[^\s"']+|filemoon\.[^\s"']+|streamtape\.com|mixdrop)[^\s"']+""")
        
        hostRegex.findAll(contentToSearch).forEach { match ->
            val url = match.value.replace("\\/", "/")
            loadExtractor(url, subtitleCallback, callback)
        }

        return true
    }
}
