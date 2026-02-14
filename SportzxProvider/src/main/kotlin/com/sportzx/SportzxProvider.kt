package com.sportzx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SportzxProvider : MainAPI() {
    override var mainUrl = "https://sportzx.cc"
    override var name = "SportzX"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Live)

    private val headers = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get("$mainUrl/sportzx-live/", headers = headers).document
        val items = mutableListOf<SearchResponse>()

        doc.select("div.elementor-widget-container h3").forEach { 
            val title = it.text().trim()
            if (title.isNotEmpty()) {
                items.add(newLiveSearchResponse(
                    title,
                    "$mainUrl/en-vivo/",
                    TvType.Live
                ))
            }
        }
        return newHomePageResponse(listOf(HomePageList("Eventi Live", items)), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("title")?.text()?.replace(" - SportzX TV", "") ?: "Live Stream"

        // Usiamo l'helper per LiveStreamLoadResponse
        return newLiveStreamLoadResponse(title, url, url) {
            this.apiName = this@SportzxProvider.name
            this.type = TvType.Live
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document
        
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("topstream") || src.contains("clonemy") || src.contains("stream")) {
                val iframeRes = app.get(src, referer = data, headers = headers).text
                
                val m3u8Regex = Regex("""(?:file|source|src)\s*:\s*["'](https?.*?\.m3u8.*?)["']""")
                val foundUrl = m3u8Regex.find(iframeRes)?.groupValues?.get(1)

                if (foundUrl != null) {
                    // Usiamo l'helper newExtractorLink per evitare il warning/errore di deprecazione
                    // Parametri obbligatori: source, name, url
                    callback.invoke(
                        newExtractorLink(
                            "SportzX",
                            "HD",
                            foundUrl
                        ) {
                            this.referer = src
                            this.quality = Qualities.P1080.value
                            this.isM3u8 = true
                        }
                    )
                }
            }
        }
        return true
    }
}
