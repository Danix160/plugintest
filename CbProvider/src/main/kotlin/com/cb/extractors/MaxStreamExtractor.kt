package com.cb.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType // FIX: Importazione mancante
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
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36",
                "Accept" to "*/*"
            )
            
            val responseBody = app.get(url, headers = headers).text

            val packedPrefix = "eval(function(p,a,c,k,e,d)"
            if (responseBody.contains(packedPrefix)) {
                val scriptData = responseBody.substringAfter(packedPrefix)
                val script = packedPrefix + scriptData.substringBefore(")))") + ")))"
                
                // Decriptiamo il codice JavaScript (Packer)
                val unpackedScript = getAndUnpack(script)
                
                // Estraiamo l'URL del video dal risultato decriptato
                val videoUrl = unpackedScript.substringAfter("src:\"", "").substringBefore("\"", "")

                if (videoUrl.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,              // Argomento 1: source
                            this.name,              // Argomento 2: name
                            videoUrl,               // Argomento 3: url (FIX: prima era 'src')
                            ExtractorLinkType.M3U8  // Argomento 4: type
                        ) {
                            // Blocco lambda per proprietà aggiuntive
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } else {
                Log.d("MaxStream", "Script packed non trovato")
            }
        } catch (e: Exception) {
            Log.e("MaxStream", "Errore: ${e.message}")
        }
    }
}
