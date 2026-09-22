package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HdFilmCehennemiProvider : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override var lang = "tr"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/128.0.0.0 Safari/537.36"

    private val requestHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val safePage = page.coerceAtLeast(1)

        val url = if (safePage == 1) {
            "$mainUrl/"
        } else {
            "$mainUrl/page/$safePage/"
        }

        return try {
            val document = app.get(
                url = url,
                headers = requestHeaders
            ).document

            val results = parseSearchResults(document)

            newHomePageResponse(
                list = listOf(
                    HomePageList(
                        name = "Son Eklenenler",
                        list = results,
                        isHorizontalImages = true
                    )
                ),
                hasNext = results.isNotEmpty()
            )
        } catch (error: Exception) {
            logError("Ana sayfa yüklenemedi: $url", error)
            newHomePageResponse(
                list = emptyList(),
                hasNext = false
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder
            .encode(query.trim(), Charsets.UTF_8.name())
            .replace("+", "%20")

        val searchUrls = listOf(
            "$mainUrl/search/$encodedQuery/",
            "$mainUrl/?s=$encodedQuery"
        )

        for (searchUrl in searchUrls) {
            try {
                val response = app.get(
                    url = searchUrl,
                    headers = requestHeaders
                )

                val results = parseSearchResults(response.document)

                if (results.isNotEmpty()) {
                    return results
                }
            } catch (error: Exception) {
                logError("Arama başarısız: $searchUrl", error)
            }
        }

        return emptyList()
    }

    private fun parseSearchResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val selector = """
            article,
            .movie-box,
            .movie-item,
            .film-box,
            .film-item,
            .card,
            .poster
        """.trimIndent().replace("\n", "")

        return document
            .select(selector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { normalizeUrl(it.url) }
    }
        private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = when {
            tagName() == "a" -> this
            else -> selectFirst("a[href]")
        } ?: return null

        val href = normalizeUrl(linkElement.attr("href"))

        if (!isContentUrl(href)) {
            return null
        }

        val image = selectFirst("img")

        val title = firstNonBlank(
            selectFirst("h1")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".title")?.text(),
            selectFirst(".film-title")?.text(),
            selectFirst(".movie-title")?.text(),
            linkElement.attr("title"),
            image?.attr("alt")
        )?.trim()

        if (title.isNullOrBlank()) {
            return null
        }

        val poster = image?.let {
            firstNonBlank(
                it.attr("data-src"),
                it.attr("data-lazy-src"),
                it.attr("data-original"),
                it.attr("src")
            )?.let(::normalizeUrl)
        }

        val type = if (isSeriesUrl(href)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return newMovieSearchResponse(
            name = title,
            url = href,
            type = type
        ) {
            posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = normalizeUrl(url)

        return try {
            val document = app.get(
                url = normalizedUrl,
                headers = requestHeaders
            ).document

            val title = firstNonBlank(
                document.selectFirst("h1")?.text(),
                document.selectFirst("h2")?.text(),
                document.selectFirst("meta[property=og:title]")?.attr("content"),
                document.title()
            )?.trim() ?: "Bilinmeyen içerik"

            val posterElement: Element? = document.selectFirst(
               "meta[property=og:image], " +
               "div.poster img, " +
               ".poster img, " +
               ".movie-poster img, " +
               "article img"
         )

            val poster: String? = posterElement?
                    firstNonBlank(
                        it.attr("content"),
                        it.attr("data-src"),
                        it.attr("data-lazy-src"),
                        it.attr("src")
                    )?.let(::normalizeUrl)
                }

            val plot = firstNonBlank(
                document.selectFirst("meta[name=description]")?.attr("content"),
                document.selectFirst("meta[property=og:description]")?.attr("content"),
                document.selectFirst(".description")?.text(),
                document.selectFirst(".overview")?.text(),
                document.selectFirst(".story")?.text(),
                document.selectFirst(".post-content")?.text()
            )?.trim()

            if (isSeriesUrl(normalizedUrl)) {
                val episodes = parseEpisodes(document)

                newTvSeriesLoadResponse(
                    name = title,
                    url = normalizedUrl,
                    type = TvType.TvSeries,
                    episodes = episodes
                ) {
                    posterUrl = poster
                    this.plot = plot
                }
            } else {
                newMovieLoadResponse(
                    name = title,
                    url = normalizedUrl,
                    type = TvType.Movie,
                    dataUrl = normalizedUrl
                ) {
                    posterUrl = poster
                    this.plot = plot
                }
            }
        } catch (error: Exception) {
            logError("Detay sayfası yüklenemedi: $normalizedUrl", error)

            newMovieLoadResponse(
                name = "İçerik yüklenemedi",
                url = normalizedUrl,
                type = TvType.Movie,
                dataUrl = normalizedUrl
            )
        }
    }
    private fun parseEpisodes(
        document: org.jsoup.nodes.Document
    ): List<Episode> {
        val selector = """
            .episode-list a[href],
            .episodes a[href],
            ul.episodes a[href],
            .season-list a[href],
            .seasons-list a[href],
            a[href*="/bolum"],
            a[href*="-bolum-"]
        """.trimIndent().replace("\n", "")

        return document
            .select(selector)
            .mapNotNull { element ->
                val href = normalizeUrl(element.attr("href"))

                if (!isContentUrl(href)) {
                    return@mapNotNull null
                }

                val episodeName = firstNonBlank(
                    element.text(),
                    element.attr("title"),
                    element.attr("aria-label")
                )?.trim()

                if (episodeName.isNullOrBlank()) {
                    return@mapNotNull null
                }

                newEpisode(href) {
                    name = episodeName
                }
            }
            .distinctBy { normalizeUrl(it.data) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(data)
        var found = false

        try {
            val document = app.get(
                url = pageUrl,
                headers = requestHeaders
            ).document

            val candidateUrls = linkedSetOf<String>()

            /*
             * Ana sayfadaki gerçek yapı:
             *
             * <div class="video-container">
             *     <iframe class="close" src="..." data-src="...">
             * </iframe>
             * </div>
             */

            document
                .select(
                    "div.video-container iframe, " +
                        ".hdmv-play-container iframe, " +
                        ".play-that-video iframe, " +
                        "iframe[src], iframe[data-src]"
                )
                .forEach { iframe ->
                    addCandidate(
                        candidateUrls,
                        iframe.attr("src")
                    )

                    addCandidate(
                        candidateUrls,
                        iframe.attr("data-src")
                    )
                }

            document
                .select(
                    "video source[src], " +
                        "video[src], " +
                        "source[src]"
                )
                .forEach { source ->
                    addCandidate(
                        candidateUrls,
                        source.attr("src")
                    )
                }

            document
                .select(
                    "[data-video], " +
                        "[data-player], " +
                        "[data-stream], " +
                        "[data-file]"
                )
                .forEach { element ->
                    addCandidate(candidateUrls, element.attr("data-video"))
                    addCandidate(candidateUrls, element.attr("data-player"))
                    addCandidate(candidateUrls, element.attr("data-stream"))
                    addCandidate(candidateUrls, element.attr("data-file"))
                }

            for (candidate in candidateUrls) {
                if (candidate.isBlank()) {
                    continue
                }

                try {
                    /*
                     * Embed URL'si başka domaine gittiği için referer olarak
                     * ana film sayfası gönderiliyor.
                     */
                    val extractorFound = loadExtractor(
                        url = candidate,
                        referer = pageUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )

                    if (extractorFound) {
                        found = true
                    }
                } catch (extractorError: Exception) {
                    logError(
                        "Extractor başarısız: $candidate",
                        extractorError
                    )
                }
            }
        } catch (error: Exception) {
            logError("Video kaynakları yüklenemedi: $pageUrl", error)
        }

        return found
    }

    private fun addCandidate(
        candidates: MutableSet<String>,
        rawUrl: String?
    ) {
        if (rawUrl.isNullOrBlank()) {
            return
        }

        val url = normalizeUrl(rawUrl)

        if (
            url.startsWith("http://") ||
            url.startsWith("https://")
        ) {
            candidates.add(url)
        }
    }

    private fun normalizeUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) {
            return ""
        }

        val value = rawUrl.trim()

        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }
    private fun isSeriesUrl(url: String): Boolean {
        return url.contains("/dizi/") ||
            url.contains("/series/") ||
            url.contains("/tv/")
    }

    private fun isContentUrl(url: String): Boolean {
        if (url.isBlank()) {
            return false
        }

        if (
            url == mainUrl ||
            url == "$mainUrl/" ||
            url.contains("/kategori/") ||
            url.contains("/category/") ||
            url.contains("/imdb/") ||
            url.contains("/sayfa/") ||
            url.contains("/page/")
        ) {
            return false
        }

        return url.startsWith("$mainUrl/")
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull {
            !it.isNullOrBlank()
        }
    }

    private fun logError(message: String, error: Throwable) {
        println(
            "HDFilmCehennemi: $message | " +
                "${error::class.simpleName}: ${error.message}"
        )
    }
}
