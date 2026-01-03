
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class OnlineseriePlugin: Plugin() {
    override fun load(context: Context) {
        // Registra il provider definito sopra nella classe ToonItaliaProvider
        registerMainAPI(OnlineserieProvider())
    }
}
