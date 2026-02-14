package com.cb.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import android.util.Base64
import android.util.Log

class MaxStreamExtractor : ExtractorApi() {
    override var name = "MaxStream"
    override var mainUrl = "https://maxstream.video"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "Referer" to "https://maxstream.video/",
                "X-Requested-With" to "XMLHttpRequest"
            )

            val response = app.get(url, headers = headers)
            val responseBody = response.text

            var videoUrl: String? = null

            // --- STRATEGIA 1: PACKER (eval) ---
            if (responseBody.contains("eval(function(p,a,c,k,e,d)")) {
                val unpacked = getAndUnpack(responseBody)
                videoUrl = Regex("""file\s*:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
            }

            // --- STRATEGIA 2: JSON DATA (Nuovo sistema) ---
            if (videoUrl == null) {
                // Alcuni nuovi player salvano i dati in un attributo 'data-config' o 'data-src'
                videoUrl = Regex("""data-config\s*=\s*"([^"]+)"""").find(responseBody)?.groupValues?.get(1)?.let {
                    // Se è Base64, lo decodifichiamo
                    if (!it.startsWith("http")) {
                        try { String(Base64.decode(it, Base64.DEFAULT)) } catch (e: Exception) { null }
                    } else it
                }
            }

            // --- STRATEGIA 3: REGEX DIRETTA ---
            if (videoUrl == null) {
                videoUrl = Regex("""(?:file|src|url)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""").find(responseBody)?.groupValues?.get(1)
            }

            // --- VALIDAZIONE E INVIO ---
            videoUrl?.let { link ->
                val finalUrl = if (link.startsWith("//")) "https:$link" else link
                
                // Log di debug per confermare l'estrazione
                Log.d("MaxStream", "Link trovato: $finalUrl")

                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        finalUrl,
                        if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = url
                    }
                )
            } ?: Log.e("MaxStream", "Fallito: Nessun pattern riconosciuto. HTML: ${responseBody.take(200)}")

        } catch (e: Exception) {
            Log.e("MaxStream", "Errore: ${e.message}")
        }
    }
}
