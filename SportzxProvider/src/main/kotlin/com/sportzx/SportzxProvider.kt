package com.sportzx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import android.util.Log

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

        // Seleziona i titoli degli eventi live
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
        
        // Cerca gli iframe che contengono i player video
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            // Filtra solo gli iframe dei server conosciuti
            if (src.contains("topstream") || src.contains("clonemy") || src.contains("stream")) {
                try {
                    val iframeRes = app.get(src, referer = data, headers = headers).text
                    
                    // Regex per estrarre il link .m3u8 dal codice sorgente dell'iframe
                    val m3u8Regex = Regex("""(?:file|source|src)\s*:\s*["'](https?.*?\.m3u8.*?)["']""")
                    val foundUrl = m3u8Regex.find(iframeRes)?.groupValues?.get(1)

                    if (foundUrl != null) {
                        // Utilizzo della sintassi confermata dall'esempio funzionante
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "HD",
                                url = foundUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = src
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("SportzX", "Errore nel caricamento del link: ${e.message}")
                }
            }
        }
        return true
    }
}
