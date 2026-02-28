override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = commonHeaders)
        val html = response.text
        val document = response.document

        // 1. CERCA L'ID NEL JS O NEGLI IFRAME (Metodo LoadM)
        // Estraiamo l'ID provando diversi pattern (inclusi quelli negli attributi data-src)
        val videoId = Regex("""(?:id|video_id)["']?\s*[:=]\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
            ?: document.select("iframe[src*=loadm.cam], iframe[data-src*=loadm.cam]").firstNotNullOfOrNull { 
                val src = it.attr("src").ifEmpty { it.attr("data-src") }
                Regex("""/e/([^"'?]+)""").find(src)?.groupValues?.get(1)
            }

        if (videoId != null) {
            val apiUrl = "https://loadm.cam/api/v1/video?id=$videoId&r=guardaplay.space"
            try {
                val apiRes = app.get(apiUrl, headers = mapOf(
                    "Referer" to "https://loadm.cam/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*"
                ))

                // LoadM spesso restituisce JSON. Se non lo è, cerchiamo l'URL nel testo.
                val body = apiRes.text
                val finalUrl = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4)[^\s"'<>]*""").find(body)?.value
                    ?: Regex("""["']file["']\s*:\s*["']([^"']+)""").find(body)?.groupValues?.get(1)

                if (finalUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            "LoadM",
                            "LoadM - Guardaplay",
                            finalUrl.replace("\\/", "/"),
                            if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = "https://loadm.cam/"
                        }
                    )
                }
            } catch (e: Exception) { /* API Error */ }
        }

        // 2. ESTRATTORI STANDARD (Cerca in tutti gli iframe)
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.startsWith("//")) src = "https:$src"
            
            if (src.isNotEmpty() && !src.contains("google") && !src.contains("youtube") && !src.contains("facebook")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // 3. FALLBACK: CERCA LINK DIRETTI NASCOSTI NEL JS (VOE, StreamWish, etc.)
        val jsLinks = Regex("""https?://(?:vidhide|voe|streamwish|mixdrop|filemoon|vood|maxstream)[^\s"'<>\\\/]+""")
        jsLinks.findAll(html).forEach { match ->
            val url = match.value.replace("\\/", "/")
            loadExtractor(url, subtitleCallback, callback)
        }

        return true
    }
