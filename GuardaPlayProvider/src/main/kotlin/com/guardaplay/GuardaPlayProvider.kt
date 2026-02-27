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
        
        // Evita duplicati: prendi solo i 'li' se presenti, altrimenti gli 'article'
        val items = document.select("li[id^=post-]").ifEmpty { 
            document.select("article.post") 
        }.mapNotNull { 
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

        val year = this.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = headers).document
        
        return document.select("li[id^=post-], article.post").mapNotNull { 
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

        // 1. Ricerca Iframe (Loadm, Vood, etc.)
        val document = response.document
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("loadm.cam") || src.contains("vood.xyz")) {
                processLoadm(src, callback)
            } else if (src.isNotEmpty() && !src.contains("google") && !src.contains("youtube")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // 2. Ricerca Link nei sorgenti JS (per SPA)
        val regex = Regex("""https?://(?:vidhide|voe|streamwish|mixdrop|filemoon|ryderjet|loadm|vood)[^\s"'<>\\\/]+""")
        regex.findAll(html).forEach { match ->
            val url = match.value.replace("\\/", "/")
            if (url.contains("loadm.cam")) {
                processLoadm(url, callback)
            } else {
                loadExtractor(url, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun processLoadm(url: String, callback: (ExtractorLink) -> Unit) {
        // Estraiamo l'ID dall'URL (es: id=61illa)
        val videoId = Regex("""id=([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        
        // Simuliamo la chiamata API trovata nel file HAR
        val apiUrl = "https://loadm.cam/api/v1/video?id=$videoId&r=guardaplay.space"
        
        try {
            val apiRes = app.get(apiUrl, headers = mapOf(
                "Referer" to "https://loadm.cam/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "Accept" to "*/*"
            ))

            // L'API di loadm spesso restituisce direttamente il file m3u8 o un JSON
            // Cerchiamo URL m3u8 o mp4 nella risposta
            val body = apiRes.text
            val videoUrl = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4)""").find(body)?.value
            
            if (videoUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        "LoadM",
                        "LoadM - Guardaplay",
                        videoUrl,
                        if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = "https://loadm.cam/"
                    }
                )
            }
        } catch (e: Exception) {
            // Log errore opzionale
        }
    }
}
