package com.cb.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
                    // Usiamo il costruttore diretto di ExtractorLink per evitare problemi con newExtractorLink
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl,
                            referer = continueUrl,
                            quality = Qualities.P1080.value,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MaxStream", "Errore estrazione: ${e.message}")
        }
    }
}
