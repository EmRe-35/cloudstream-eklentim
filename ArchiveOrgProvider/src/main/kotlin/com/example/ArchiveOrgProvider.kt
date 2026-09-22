package com.example

import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class ArchiveOrgProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Archive.org"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "https://archive.org/advancedsearch.php?q=mediatype:movies&fl[]=identifier&fl[]=title&rows=20&page=$page&output=json"
        val json = JSONObject(app.get(url).text)
        val docs = json.getJSONObject("response").getJSONArray("docs")

        val items = (0 until docs.length()).map { i ->
            val doc = docs.getJSONObject(i)
            val id = doc.getString("identifier")
            val title = doc.optString("title", id)
            newMovieSearchResponse(title, id, TvType.Movie) {
                this.posterUrl = "https://archive.org/services/img/$id"
            }
        }
        return newHomePageResponse("Archive.org Filmleri", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://archive.org/advancedsearch.php?q=title:($query)+AND+mediatype:movies&fl[]=identifier&fl[]=title&rows=20&output=json"
        val json = JSONObject(app.get(url).text)
        val docs = json.getJSONObject("response").getJSONArray("docs")

        return (0 until docs.length()).map { i ->
            val doc = docs.getJSONObject(i)
            val id = doc.getString("identifier")
            val title = doc.optString("title", id)
            newMovieSearchResponse(title, id, TvType.Movie) {
                this.posterUrl = "https://archive.org/services/img/$id"
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url
        val metaUrl = "https://archive.org/metadata/$id"
        val json = JSONObject(app.get(metaUrl).text)
        val metadata = json.getJSONObject("metadata")
        val title = metadata.optString("title", id)
        val description = metadata.optString("description", "")

        return newMovieLoadResponse(title, url, TvType.Movie, id) {
            this.posterUrl = "https://archive.org/services/img/$id"
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data
        val metaUrl = "https://archive.org/metadata/$id"
        val json = JSONObject(app.get(metaUrl).text)
        val files = json.getJSONArray("files")

        var found = false
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val name = file.getString("name")
            if (name.endsWith(".mp4")) {
                val videoUrl = "https://archive.org/download/$id/$name"
                callback(
    newExtractorLink(
        source = name,
        name = name,
        url = videoUrl
    ) {
        this.referer = mainUrl
        this.quality = Qualities.Unknown.value
        this.isM3u8 = false
    }
)
                found = true
            }
        }
        return found
    }
}
