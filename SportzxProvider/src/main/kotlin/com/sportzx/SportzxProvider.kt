package com.sportzx

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType

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
        // Usiamo l'endpoint specifico per gli eventi live visto nei tuoi file
        val doc = app.get("$mainUrl/sportzx-live/", headers = headers).document
        val items = mutableListOf<SearchResponse>()

        // Selettore aggiornato basato sull'HTML reale del sito (elementor-heading-title)
        doc.select("h3.elementor-heading-title a, .elementor-widget-container h3 a").forEach { 
            val title = it.text().trim()
            val href = it.attr("href")
            if (title.isNotEmpty() && href.isNotEmpty()) {
                items.add(newLiveSearchResponse(
                    title,
                    href,
                    TvType.Live
                ))
            }
        }
        
        // Se la lista è vuota, proviamo un selettore più generico per i titoli
        if (items.isEmpty()) {
            doc.select("h3").forEach {
                val title = it.text().trim()
                if (title.contains("vs", ignoreCase = true) || title.contains("Live", ignoreCase = true)) {
                    items.add(newLiveSearchResponse(title, "$mainUrl/en-vivo/", TvType.Live))
                }
            }
        }

        return newHomePageResponse(listOf(HomePageList("Eventi Sportivi", items)), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        // Estraiamo il titolo dalla pagina o dal tag og:title
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content") 
                    ?: doc.selectFirst("title")?.text()?.replace(" - SportzX TV", "") 
                    ?: "Live Stream"

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
        
        // Analizziamo sia gli iframe diretti che i link nel modal (vsc-stream-link)
        val sources = doc.select("iframe").map { it.attr("src") }.toMutableList()
        doc.select("a.vsc-stream-link").forEach { sources.add(it.attr("href")) }

        sources.distinct().forEach { src ->
            if (src.contains("topstream") || src.contains("clonemy") || src.contains("stream") || src.contains("m3u8")) {
                try {
                    val res = app.get(src, referer = data, headers = headers)
                    val iframeRes = res.text
                    
                    // Cerchiamo il file .m3u8 nel sorgente
                    val m3u8Regex = Regex("""(?:file|source|src|url)\s*[:=]\s*["'](https?.*?\.m3u8.*?)["']""")
                    val foundUrl = m3u8Regex.find(iframeRes)?.groupValues?.get(1)

                    if (foundUrl != null) {
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "Server " + (src.substringAfter("://").substringBefore(".")),
                                url = foundUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = src
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("SportzX", "Errore link $src: ${e.message}")
                }
            }
        }
        return true
    }
}
