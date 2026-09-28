package com.example.yabancidizi

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class MolystreamExtractor : ExtractorApi() {
    override var name = "Molystream"
    override var mainUrl = "https://dbx.molystream.org"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()

        val html = try {
            app.get(url, referer = referer).text
        } catch (e: Exception) {
            return emptyList()
        }

        // .m3u8 uzantılı linkleri HTML içinde ara
        val m3u8Regex = Regex("""https?://[^"'\s\\]+?\.m3u8[^"'\s\\]*""")
        m3u8Regex.findAll(html).forEach { match ->
            links.add(
                newExtractorLink(
                    source = this.name,
                    name = "Molystream",
                    url = match.value,
                    referer = this.mainUrl
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return links
    }
}
