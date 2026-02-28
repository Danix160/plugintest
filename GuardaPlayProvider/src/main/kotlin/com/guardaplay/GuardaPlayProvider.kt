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

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
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
        
        val items = document.select("li[id^=post-], article.post").mapNotNull { 
            it.toSearchResult() 
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a.lnk-blk")?.attr("href") 
            ?: this.selectFirst("a")?.attr("href") 
            ?: return null

        val title = this.selectFirst("h2.entry-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.replace("Image ", "")
            ?: return null
        
        val img = this.selectFirst("img")
        val posterUrl = img?.let { 
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.startsWith("//")) "https:$src" else src
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = commonHeaders).document
        
        return document.select("li[id^=post-], article.post").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".post-thumbnail img, .poster img")?.let { img ->
            val src = img.attr("src").ifEmpty { img.attr("data-src") }
            if (src.startsWith("//")) "https:$src" else src
        }
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
        val html = response.text

        // 1. ESTRAZIONE DIRETTA PER LOADM (Basata sul traffico analizzato)
        val videoId = Regex("""/e/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""id["']?\s*[:=]\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""loadm\.cam/e/([^"'?]+)""").find(html)?.groupValues?.get(1)

        if (videoId != null) {
            val apiUrl = "https://loadm.cam/api/v1/video?id=$videoId&r=guardaplay.space"
            
            try {
                val apiRes = app.get(apiUrl, headers = mapOf(
                    "Referer" to "https://loadm.cam/",
                    "User-Agent" to commonHeaders["User-Agent"]!!,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*"
                ))

                val body = apiRes.text
                
                // Cerchiamo il link sorgente master. Nota: può finire in .txt o .m3u8
                val masterLink = Regex("""https?://[^\s"'<>]+cf-master\.[0-9]+\.txt""").find(body)?.value
                    ?: Regex("""https?://[^\s"'<>]+master\.m3u8[^\s"'<>]*""").find(body)?.value
                    ?: Regex("""["']file["']\s*:\s*["']([^"']+)""").find(body)?.groupValues?.get(1)

                if (masterLink != null) {
                    val cleanUrl = masterLink.replace("\\/", "/")
                    
                    callback.invoke(
                        ExtractorLink(
                            source = "LoadM",
                            name = "LoadM (Auto)",
                            url = cleanUrl,
                            referer = "https://loadm.cam/",
                            quality = Qualities.Unknown.value,
                            type = if (cleanUrl.contains(".m3u8") || cleanUrl.contains(".txt")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            headers = mapOf(
                                "Origin" to "https://loadm.cam",
                                "Accept" to "*/*"
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                // Errore silenzioso per passare ad altri estrattori
            }
        }

        // 2. TENTATIVO ESTRATTORI STANDARD (Iframe e Link nel JS)
        val doc = response.document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("google") && !src.contains("youtube")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        val regex = Regex("""https?://(?:vidhide|voe|streamwish|mixdrop|filemoon|ryderjet|vood|embedwish)[^\s"'<>\\\/]+""")
        regex.findAll(html).forEach { match ->
            loadExtractor(match.value.replace("\\/", "/"), subtitleCallback, callback)
        }

        return true
    }
}
