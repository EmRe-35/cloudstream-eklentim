package com.example.yabancidizi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class YabanciDiziProvider : MainAPI() {
    override var mainUrl = "https://yabancidizi.news"
    override var name = "Yabancı Dizi"
    // ... geri kalan kod aynı
}

class YabanciDiziProvider : MainAPI() {
    override var mainUrl = "https://yabancidizi.news"
    override var name = "Yabancı Dizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Anasayfa",
        "dizi-izle-hd" to "TV Dizileri",
        "film-izle-hd" to "Sinema Filmleri",
        "trends" to "Trendler",
        "kesfet" to "Keşfet"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) mainUrl else "$mainUrl/${request.data}"
        val document = app.get(url).document

        val items = mutableListOf<HomePageList>()

        // Ana sayfadaki posterleri çek (birden fazla seçici deniyoruz)
        val posters = (
            document.select("div.poster-media a") +
            document.select("li.mofy-moviesli a") +
            document.select("ul.clearfix li a[href*=/dizi/], ul.clearfix li a[href*=/film/]") +
            document.select("div.featured-segment a")
        )
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (posters.isNotEmpty()) {
            items.add(HomePageList(request.name, posters))
        }

        return newHomePageResponse(items, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href").takeIf { it.isNotBlank() && !it.startsWith("#") } ?: return null
        val title = this.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("h2")?.text()?.trim()
            ?: this.selectFirst("h6")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }

        val fullUrl = if (href.startsWith("http")) href else "$mainUrl/${href.trimStart('/')}"
        val isMovie = href.contains("/film/")

        return newMovieSearchResponse(
            title,
            fullUrl,
            if (isMovie) TvType.Movie else TvType.TvSeries
        ) {
            this.posterUrl = poster?.let {
                if (it.startsWith("http")) it else "$mainUrl/${it.trimStart('/')}"
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Sitenin SPA yapısı nedeniyle bu endpoint çalışmayabilir.
        // Network sekmesinden gerçek arama API'sini bulup buraya yazın.
        val searchUrl = "$mainUrl/search?q=${query.replace(" ", "+")}"
        return try {
            val document = app.get(searchUrl).document
            (document.select("a[href*=/dizi/], a[href*=/film/]") +
             document.select("div.search-result a, li.result a"))
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.poster img")?.attr("src")

        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("div.description, p.description, div.summary")?.text()?.trim()

        val isMovie = url.contains("/film/")

        val episodes = if (isMovie) {
            // Filmler tek bölüm olarak eklenir
            listOf(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                }
            )
        } else {
            // Dizi bölümlerini çek
            parseEpisodes(document, url)
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            if (isMovie) TvType.Movie else TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster?.let {
                if (it.startsWith("http")) it else "$mainUrl/${it.trimStart('/')}"
            }
            this.plot = description
        }
    }

    private suspend fun parseEpisodes(document: org.jsoup.nodes.Document, seriesUrl: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    val episodeLinks = mutableListOf<org.jsoup.nodes.Element>()

    // Doğrudan bölüm linklerini ara
    episodeLinks.addAll(document.select("a[href*=/bolum-]"))

    // Eğer hiç bölüm linki yoksa, sezon sayfalarına gidip bölümleri çek
    if (episodeLinks.isEmpty()) {
        val seasonLinks = document.select("a[href*=/sezon-]")
            .map { it.attr("href") }
            .distinct()

        seasonLinks.forEach { seasonHref ->
            val seasonUrl = if (seasonHref.startsWith("http")) seasonHref
                            else "$mainUrl/${seasonHref.trimStart('/')}"
            val seasonDoc = try {
                app.get(seasonUrl).document
            } catch (e: Exception) {
                return@forEach
            }
            episodeLinks.addAll(seasonDoc.select("a[href*=/bolum-]"))
        }
    }

    episodeLinks.distinctBy { it.attr("href") }.forEachIndexed { index, el ->
        val href = el.attr("href")
        val epUrl = if (href.startsWith("http")) href
                    else "$mainUrl/${href.trimStart('/')}"

        val episodeNumber = Regex("""bolum-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
        val seasonNumber = Regex("""sezon-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()

        val epTitle = el.selectFirst("h2, h6, .episode-no")?.text()?.trim()
            ?: el.text().trim().take(50)

        episodes.add(
            newEpisode(epUrl) {
                this.name = epTitle.ifBlank {
                    "S${seasonNumber ?: 1} B${episodeNumber ?: (index + 1)}"
                }
                this.episode = episodeNumber ?: (index + 1)
                this.season = seasonNumber ?: 1
            }
        )
    }

    return episodes
}

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // iframe'leri bul
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                val fullSrc = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(fullSrc, data, subtitleCallback, callback)
                found = true
            }
        }

        // Bazı siteler rapidrame_id parametresi kullanır — onu da deneyelim
        val rapidrameId = Regex("""rapidrame_id=([a-zA-Z0-9]+)""")
            .find(document.html())?.groupValues?.get(1)

        if (rapidrameId != null) {
            val embedUrl = "https://dbx.molystream.org/embed/$rapidrameId/q/1"
            loadExtractor(embedUrl, data, subtitleCallback, callback)
            found = true
        }

        return found
    }
}
