package com.cineblog // Deve essere uguale a quello in cima al file

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineblogPlugin: Plugin() {
    override fun load(context: Context) {
        // Registra il provider definito sopra nella classe ToonItaliaProvider
        registerMainAPI(CineblogProvider())
    }
}
