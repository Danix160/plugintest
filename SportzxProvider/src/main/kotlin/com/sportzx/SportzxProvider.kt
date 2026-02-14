package com.sportzx // Cambia con il tuo package

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

class SportzxProvider : MainAPI() {
    override var mainUrl = "https://sportzx.cc"
    override var name = "SportzX"
    override val hasMainPage = true
    override var lang = "it" // O "en" a seconda della preferenza
    override val supportedTypes = setOf(TvType.Live)

    // Header necessari per bypassare i controlli base
    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse {
        val document = app.get("$mainUrl/sportzx-live/", headers = commonHeaders).document
        val items = mutableListOf<LiveSearchResponse>()

        // Il sito elenca i match in blocchi. Cerchiamo i contenitori dei match.
        // Nota: se i match sono generati da JS, potremmo dover usare un Regex sul contenuto della pagina.
        document.select("div.elementor-widget-container").forEach { element ->
            val title = element.selectFirst("h3, h2")?.text() ?: ""
            if (title.isNotEmpty() && (title.contains("vs") || title.contains(":"))) {
                items.add(LiveSearchResponse(
                    title,
                    "$mainUrl/en-vivo/", // La destinazione è quasi sempre questa
                    this.name,
                    TvType.Live,
                    null
                ))
            }
        }

        return newHomePageResponse(items)
    }

    override suspend fun load(url: String): LoadResponse {
        // Poiché il sito usa un modal, carichiamo la pagina e mostriamo i server disponibili
        val document = app.get(url, headers = commonHeaders).document
        val title = document.selectFirst("title")?.text()?.replace(" - SportzX TV", "") ?: "Live Stream"

        return LiveStreamLoadResponse(
            title,
            url,
            this.name,
            url,
            null,
            dataUrl = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Carichiamo la pagina del player
        val res = app.get(data, headers = commonHeaders)
        val document = res.document

        // 2. Cerchiamo l'iframe. Dal file HAR, il player è spesso dentro un iframe
        // che punta a domini come topstream.online, amandatv, ecc.
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("http")) {
                // 3. Estraiamo il link finale. Molti di questi server sono supportati
                // dagli estrattori standard di CloudStream.
                loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                
                // Se l'estrattore automatico fallisce, proviamo manualmente:
                if (src.contains("topstream") || src.contains("vidsrc")) {
                    val iframeRes = app.get(src, referer = data).text
                    val m3u8 = Regex("""(https?.*?\.m3u8.*?)["']""").find(iframeRes)?.groupValues?.get(1)
                    
                    if (m3u8 != null) {
                        callback.invoke(
                            ExtractorLink(
                                "SportzX",
                                "Server Live",
                                m3u8,
                                referer = src,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true
                            )
                        )
                    }
                }
            }
        }
        return true
    }
}
