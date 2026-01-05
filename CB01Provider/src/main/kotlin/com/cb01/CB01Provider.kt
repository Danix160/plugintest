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
        val plot = document.selectFirst(".entry-content p, .card-text")?.text()
        
        val isTvSeries = url.contains("/serietv/") || document.selectFirst(".sp-wrap") != null

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // 1. Identifichiamo i blocchi Spoiler (Stagioni)
            document.select("div.sp-wrap").forEachIndexed { index, wrap ->
                val seasonNameFromSite = wrap.selectFirst(".sp-head")?.text()?.trim() ?: "Serie"
                val seasonNumber = index + 1
                
                // 2. Cerchiamo i link dentro lo spoiler (uprot, maxstream, ecc.)
                wrap.select(".sp-body a").forEach { a ->
                    val listUrl = a.attr("href")
                    if (listUrl.contains("uprot.net") || listUrl.contains("maxstream")) {
                        // 3. ENTRIAMO DENTRO UPROT per estrarre la vera lista episodi
                        try {
                            val listDoc = app.get(listUrl).document
                            // Cerchiamo i link agli episodi dentro uprot (spesso sono tabelle o liste di link)
                            listDoc.select("a").forEach { epLink ->
                                val finalUrl = epLink.attr("href")
                                val epName = epLink.text().trim()
                                
                                // Se il link sembra un episodio (contiene numeri o pattern)
                                if (finalUrl.startsWith("http") && !finalUrl.contains("cb01")) {
                                    episodes.add(newEpisode(finalUrl) {
                                        this.name = "$seasonNameFromSite - $epName"
                                        this.season = seasonNumber
                                    })
                                }
                            }
                        } catch (e: Exception) {
                            // Se fallisce l'estrazione profonda, aggiungiamo il link originale come fallback
                            episodes.add(newEpisode(listUrl) {
                                this.name = "$seasonNameFromSite - ${a.text()}"
                                this.season = seasonNumber
                            })
                        }
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Ora 'data' è già il link finale dell'episodio estratto da uprot
        if (loadExtractor(data, data, subtitleCallback, callback)) return true

        // Se è rimasto un link intermedio, proviamo un ultimo tentativo di scansione
        val doc = try { app.get(data).document } catch (e: Exception) { return false }
        doc.select("iframe, a").forEach { 
            val link = it.attr("src").ifEmpty { it.attr("href") }
            if (link.startsWith("http") && !link.contains("google")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
