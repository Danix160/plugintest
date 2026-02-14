package com.cb.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities // Import corretto
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink // Import per il nuovo metodo
import android.util.Log

class MaxStreamExtractor : ExtractorApi() {
    override var name = "MaxStream"
    override var mainUrl = "https://maxstream.video"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, 
        referer: String?, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1. Carica la pagina di uprot.net
            val response = app.get(url, referer = referer).text
            
            // 2. Trova il link del tasto CONTINUE
            val continueUrl = Regex("""href="(https://maxstream\.video/uprots/[^"]+)"""")
                .find(response)?.groupValues?.get(1)

            if (continueUrl != null) {
                // 3. Carica la pagina finale del video
                val finalPage = app.get(continueUrl, referer = url).text
                
                // 4. Estrae il link video finale diretto
                val videoUrl = Regex("""https://maxsa\d+\.website/watchfree/[^"']+""").find(finalPage)?.value

                if (videoUrl != null) {
                    // Usiamo newExtractorLink per evitare l'errore di deprecazione
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl,
                            referer = continueUrl,
                            quality = Qualities.P1080.value, // Qualities con la S
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MaxStream", "Errore estrazione: ${e.message}")
        }
    }
}
