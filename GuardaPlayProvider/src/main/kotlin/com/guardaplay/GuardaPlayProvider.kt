package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val hasMainPage = true
    override var lang = "it"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // ... (omesse le parti di ricerca e caricamento dei dettagli per brevità)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = commonHeaders)
        val html = response.text
        val document = response.document

        // 1. ESTRATTORE LOADM (API personalizzata)
        val videoId = Regex("""(?:id|video_id)["']?\s*[:=]\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
            ?: document.select("iframe[src*=loadm.cam], iframe[data-src*=loadm.cam]").firstNotNullOfOrNull { 
                val src = it.attr("src").ifEmpty { it.attr("data-src") }
                Regex("""/e/([^"'?]+)""").find(src)?.groupValues?.get(1)
            }

        if (videoId != null) {
            val apiUrl = "https://loadm.cam/api/v1/video?id=$videoId&r=guardaplay.space"
            try {
                val apiRes = app.get(apiUrl, headers = mapOf(
                    "Referer" to "https://loadm.cam/",
                    "X-Requested-With" to "XMLHttpRequest"
                )).text
                
                val finalUrl = Regex("""["']file["']\s*:\s*["']([^"']+)""").find(apiRes)?.groupValues?.get(1)
                
                if (finalUrl != null) {
                    // CORREZIONE LINEA 99
                    callback.invoke(
                        newExtractorLink(
                            "LoadM",
                            "LoadM - Guardaplay",
                            finalUrl.replace("\\/", "/"),
                            if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            // Questo è il blocco di inizializzazione dove i parametri sono validi
                            this.quality = Qualities.P1080.value
                            this.referer = "https://loadm.cam/"
                        }
                    )
                }
            } catch (e: Exception) { /* Errore API */ }
        }

        // 2. ESTRATTORI STANDARD (Iframe)
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.startsWith("//")) src = "https:$src"
            
            if (src.isNotEmpty() && !src.contains("google") && !src.contains("youtube")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // 3. ESTRATTORE DIRETTO VIDSTACK/LOADM (Regex manuale)
        // CORREZIONE LINEA 126
        val vidstackRegex = Regex("""src\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4))["']""")
        vidstackRegex.findAll(html).forEach { match ->
            val videoUrl = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    "GuardaPlay HQ",
                    "Direct Stream",
                    videoUrl,
                    if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = "https://loadm.cam/"
                }
            )
        }

        return true
    }
}
