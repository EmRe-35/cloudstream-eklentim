package com.example.yabancidizi

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class YabanciDiziPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(YabanciDiziProvider())
        registerExtractorAPI(MolystreamExtractor())
    }
}
