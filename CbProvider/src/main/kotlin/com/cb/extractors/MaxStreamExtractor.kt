package com.cb.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Quality
import com.lagradost.cloudstream3.SubtitleFile
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
            
            // 2. Trova il link del tasto CONTINUE (uprots/...)
            val continueUrl = Regex("""href="(https://maxstream\.video/uprots/[^"]+)"""")
                .find(response)?.groupValues?.get(1)

            if (continueUrl != null) {
                Log.d("MaxStream", "Step 1 OK: Trovato Continue URL -> $continueUrl")

                // 3. Carica la pagina finale del video
                val finalPage = app.get(continueUrl, referer = url).text
                
                // 4. Regex per il link finale (maxsaXXX.website)
                val videoUrl = Regex("""https://maxsa\d+\.website/watchfree/[^"']+""").find(finalPage)?.value

                if (videoUrl != null) {
                    Log.d("MaxStream", "Step 2 OK: Trovato Video URL -> $videoUrl")
                    
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            videoUrl,
                            referer = continueUrl,
                            quality = Quality.Unknown.value,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )

                    // OPZIONALE: Cerca sottotitoli nella pagina finale
                    // Se il sito ha tag <track>, CloudStream li aggiungerà automaticamente
                    Regex("""kind="subtitles"\s+src="([^"]+)"""").findAll(finalPage).forEach { match ->
                        val subUrl = match.groupValues[1]
                        subtitleCallback.invoke(
                            SubtitleFile("Italiano", subUrl)
                        )
                    }
                } else {
                    Log.e("MaxStream", "Errore: Link video finale non trovato nella pagina.")
                }
            } else {
                Log.e("MaxStream", "Errore: Tasto CONTINUE non trovato. Forse il sito ha cambiato layout?")
            }
        } catch (e: Exception) {
            Log.e("MaxStream", "Eccezione durante l'estrazione: ${e.message}")
        }
    }
}
