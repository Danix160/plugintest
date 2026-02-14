package com.sportzx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

        // Correzione Type Mismatch: l'ordine dei parametri per newLiveStreamLoadResponse è fondamentale
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            type = TvType.Live,
            dataUrl = url
        )
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
                    // Uso di newExtractorLink con parametri nominati per la massima sicurezza
                    callback.invoke(
                        newExtractorLink(
                            source = "SportzX",
                            name = "HD Stream",
                            url = foundUrl,
                            referer = src,
                            quality = Qualities.P1080.value,
                            isM3u8 = true
                        )
                    )
                }
            }
        }
        return true
    }
}
