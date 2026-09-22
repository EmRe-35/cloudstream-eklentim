package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

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
        val metadata = json.optJSONObject("metadata") ?: JSONObject()
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
        val responseText = app.get(metaUrl).text
        val json = JSONObject(responseText)
        val files = json.optJSONArray("files") ?: return false

        var found = false
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val fileName = file.optString("name", "")
            
            if (fileName.endsWith(".mp4", ignoreCase = true) || 
                fileName.endsWith(".mkv", ignoreCase = true) || 
                fileName.endsWith(".webm", ignoreCase = true)) {
                
                // Dosya adındaki boşluk ve özel karakterleri URL formatına dönüştürüyoruz
                val encodedFileName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                val videoUrl = "https://archive.org/download/$id/$encodedFileName"
                
                // Kalite tespiti (dosya adında veya formatta 720p, 1080p vb. geçiyorsa ayarlayabilirsiniz)
                val quality = when {
                    fileName.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
                    fileName.contains("720p", ignoreCase = true) -> Qualities.P720.value
                    fileName.contains("480p", ignoreCase = true) -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }

                callback(
                    ExtractorLink(
                        source = this.name,
                        name = fileName,
                        url = videoUrl,
                        referer = "$mainUrl/",
                        quality = quality,
                        type = ExtractorLinkType.VIDEO // Cloudstream'in direkt video dosyası olduğunu anlamasını sağlar
                    )
                )
                found = true
            }
        }
        return found
    }
}
