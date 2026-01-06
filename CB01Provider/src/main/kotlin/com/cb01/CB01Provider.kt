package com.cb01

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CB01Provider : MainAPI() {
    override var mainUrl = "https://cb01net.baby"
    override var name = "CB01"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Film",
        "$mainUrl/serietv" to "Serie TV"
    )

    // ... (getMainPage e search rimangono invariati)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1.card-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".card-image img, .poster img, .entry-content img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }
        
        val episodes = mutableListOf<Episode>()
        
        // Selettore specifico per gli spoiler di CB01 Serie TV
        document.select("div.sp-wrap").forEachIndexed { index, wrap ->
            val seasonNum = index + 1
            // Cerchiamo i link che portano a Maxstream/Uprot/AkVideo
            wrap.select(".sp-body a").forEach { a ->
                val href = a.attr("href")
                if (href.contains(Regex("maxstream|uprot|akvideo|delta"))) {
                    try {
                        // Entriamo nella pagina di Maxstream per leggere la tabella episodi
                        val listDoc = app.get(href).document
                        // Cerchiamo i link dentro la tabella (solitamente hanno classe .btn o sono in <td>)
                        listDoc.select("table td a, .list-group-item a").forEach { ep ->
                            val epUrl = ep.attr("href")
                            val epText = ep.text().trim()
                            if (epUrl.isNotEmpty() && epUrl.startsWith("http")) {
                                episodes.add(newEpisode(epUrl) {
                                    this.name = "Stagione $seasonNum - Ep. $epText"
                                    this.season = seasonNum
                                })
                            }
                        }
                    } catch (e: Exception) { }
                }
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Se il link è già un video (es. Mixdrop), caricalo direttamente
        if (loadExtractor(data, data, subtitleCallback, callback)) return true

        // Se siamo su una pagina intermedia di Maxstream/Uprot
        val doc = try { app.get(data).document } catch (e: Exception) { return false }

        // 1. Cerchiamo IFRAME (dove Maxstream nasconde il player vero)
        doc.select("iframe").forEach {
            val src = it.attr("src")
            if (src.startsWith("http") && !src.contains("google")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // 2. Cerchiamo script che caricano il video (es. jwplayer o setup)
        val scripts = doc.select("script").html()
        if (scripts.contains("file:\"")) {
            val videoUrl = scripts.substringAfter("file:\"").substringBefore("\"")
            if (videoUrl.startsWith("http")) {
                callback.invoke(
                    ExtractorLink(
                        "Maxstream",
                        "Maxstream",
                        videoUrl,
                        data,
                        Qualities.Unknown.value
                    )
                )
            }
        }

        // 3. Cerchiamo bottoni di redirect
        doc.select("a.btn, .download-link a").forEach {
            val link = it.attr("href")
            if (link.startsWith("http") && !link.contains("cb01")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
