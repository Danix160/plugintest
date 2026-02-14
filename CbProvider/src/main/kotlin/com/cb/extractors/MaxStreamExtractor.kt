package com.cb.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
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
            val response = app.get(url, referer = referer).text
            
            val continueUrl = Regex("""href="(https://maxstream\.video/uprots/[^"]+)"""")
                .find(response)?.groupValues?.get(1)

            if (continueUrl != null) {
                val finalPage = app.get(continueUrl, referer = url).text
                val videoUrl = Regex("""https://maxsa\d+\.website/watchfree/[^"']+""").find(finalPage)?.value

                if (videoUrl != null) {
                    // Firma corretta: source, name, url, [type], { initializer }
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl
                        ) {
                            // Qui impostiamo i parametri extra all'interno dell'oggetto
                            this.referer = continueUrl
                            this.quality = Qualities.P1080.value
                            this.isM3u8 = videoUrl.contains(".m3u8")
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MaxStream", "Errore estrazione: ${e.message}")
        }
    }
}
