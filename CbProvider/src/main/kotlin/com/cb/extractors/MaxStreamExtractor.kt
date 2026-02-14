package com.cb.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
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
            // 1. Setup Headers per simulare un browser reale
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,webp,image/apng,*/*;q=0.8",
                "Referer" to (referer ?: mainUrl),
                "Origin" to "https://maxstream.video"
            )
            
            val responseBody = app.get(url, headers = headers).text

            var videoUrl = ""
            val packedPrefix = "eval(function(p,a,c,k,e,d)"

            // 2. Metodo A: Tentativo di decriptazione Packer (eval)
            if (responseBody.contains(packedPrefix)) {
                try {
                    val scriptData = responseBody.substringAfter(packedPrefix)
                    val script = packedPrefix + scriptData.substringBefore(")))") + ")))"
                    val unpackedScript = getAndUnpack(script)
                    
                    // Estrazione url dall'unpacked
                    videoUrl = unpackedScript.substringAfter("src:\"", "").substringBefore("\"", "")
                    if (videoUrl.isEmpty()) {
                        videoUrl = unpackedScript.substringAfter("file:\"", "").substringBefore("\"", "")
                    }
                    Log.d("MaxStream", "Link estratto con successo via Unpacker")
                } catch (e: Exception) {
                    Log.e("MaxStream", "Errore durante l'unpacking: ${e.message}")
                }
            } 
            
            // 3. Metodo B: Fallback Regex (se il Packer fallisce o non esiste)
            if (videoUrl.isEmpty()) {
                // Cerca pattern tipo src:"https://..." o file:"https://..."
                val regex = Regex("""(?:src|file)\s*:\s*"([^"]+)"""")
                videoUrl = regex.find(responseBody)?.groupValues?.get(1) ?: ""
                if (videoUrl.isNotEmpty()) Log.d("MaxStream", "Link estratto con successo via Regex")
            }

            // 4. Invio del link al player
            if (videoUrl.isNotEmpty()) {
                // Normalizza l'URL (aggiunge protocollo se mancante)
                val finalUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl
                
                callback.invoke(
                    newExtractorLink(
                        this.name,               // Source
                        this.name,               // Name
                        finalUrl,                // URL
                        ExtractorLinkType.M3U8   // Type (MaxStream usa prevalentemente HLS/m3u8)
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                Log.d("MaxStream", "Nessun URL video trovato nella pagina")
            }

        } catch (e: Exception) {
            Log.e("MaxStream", "Errore critico estrattore: ${e.message}")
        }
    }
}
