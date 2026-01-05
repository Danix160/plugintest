package com.cb01 // Deve essere uguale a quello in cima al file

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CB01Plugin: Plugin() {
    override fun load(context: Context) {
        // Registra il provider definito sopra nella classe ToonItaliaProvider
        registerMainAPI(CB01Provider())
    }
}
