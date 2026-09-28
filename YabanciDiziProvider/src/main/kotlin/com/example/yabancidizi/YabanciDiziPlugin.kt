package com.example.yabancidizi

import android.content.Context
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class YabanciDiziPlugin : BasePlugin() {
    override fun load(context: Context) {
        registerMainAPI(YabanciDiziProvider())
        registerExtractorAPI(MolystreamExtractor())
    }
}
