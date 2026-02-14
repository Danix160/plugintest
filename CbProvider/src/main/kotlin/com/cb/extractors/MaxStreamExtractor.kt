package com.cb.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
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
            // Headers moderni per evitare blocchi dal server
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "Referer" to (referer ?: mainUrl)
            )
            
            val responseBody = app.get(url, headers = headers).text

            // Cerchiamo il codice protetto dal "Packer" (eval(function(p,a,c,k,e,d)...))
            val packedPrefix = "eval(function(p,a,c,k,e,d)"
            if (responseBody.contains(packedPrefix)) {
                val scriptData = responseBody.substringAfter(packedPrefix)
                val script = packedPrefix + scriptData.substringBefore(")))") + ")))"
                
                // Funzione integrata in CloudStream per decriptare il Javascript
                val unpackedScript = getAndUnpack(script)
                
                // Estraiamo il link del video dall'output decriptato
                val videoUrl = unpackedScript.substringAfter("src:\"", "").substringBefore("\"", "")

                if (videoUrl.isNotEmpty()) {
                    callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = src,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: ""
                this.quality = Qualities.Unknown.value
            }
        )
                }
            } else {
                Log.d("MaxStream", "Script packed non trovato nella pagina")
            }
        } catch (e: Exception) {
            Log.e("MaxStream", "Errore durante l'estrazione: ${e.message}")
        }
    }
}
