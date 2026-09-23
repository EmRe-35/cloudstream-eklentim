package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64

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

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private val homeCategories = listOf(
        "Son Eklenenler" to "$mainUrl/category/film-izle-2/",
        "Nette İlk" to "$mainUrl/category/nette-ilk-filmler-1/",
        "Tavsiye Filmler" to "$mainUrl/category/tavsiye-filmler-izle3/",
        "Aksiyon" to "$mainUrl/tur/aksiyon-filmleri-izleyin-8/",
        "Komedi" to "$mainUrl/tur/komedi-filmlerini-izleyin-2/",
        "1080p Filmler" to "$mainUrl/category/1080p-hd-film-izle-5/"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val currentPage = page.coerceAtLeast(1)
        val lists = mutableListOf<HomePageList>()

        for ((categoryName, baseUrl) in homeCategories) {

            try {
                val pageUrl = buildPageUrl(
                    baseUrl,
                    currentPage
                )

                val document = app.get(
                    pageUrl,
                    headers = defaultHeaders
                ).document

                val results = parseResults(document)

                if (results.isNotEmpty()) {
                    lists.add(
                        HomePageList(
                            categoryName,
                            results,
                            isHorizontalImages = true
                        )
                    )
                }

            } catch (e: Exception) {
                logError(
                    "Kategori yüklenemedi: $categoryName",
                    e
                )
            }
        }

        return newHomePageResponse(
            lists,
            hasNext = lists.any { it.list.isNotEmpty() }
        )
    }

    private fun buildPageUrl(
        baseUrl: String,
        page: Int
    ): String {

        if (page <= 1) {
            return baseUrl
        }

        return baseUrl.trimEnd('/') + "/page/$page/"
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = URLEncoder
            .encode(query.trim(), "UTF-8")
            .replace("+", "%20")

        val urls = listOf(
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/search/$encodedQuery/"
        )

        for (url in urls) {

            try {

                val document = app.get(
                    url,
                    headers = defaultHeaders
                ).document

                val results = parseResults(document)

                if (results.isNotEmpty()) {
                    return results
                }

            } catch (e: Exception) {

                logError(
                    "Arama başarısız: $url",
                    e
                )
            }
        }

        return emptyList()
    }

    private fun parseResults(
        document: Document
    ): List<SearchResponse> {

        val results = mutableListOf<SearchResponse>()

        val links = document.select(
            "a[href]"
        )

        for (link in links) {

            try {

                val url = normalizeUrl(
                    link.attr("href")
                )

                if (!isValidContentUrl(url)) {
                    continue
                }

                val result = link.toSearchResult()

                if (result != null) {
                    results.add(result)
                }

            } catch (_: Exception) {
            }
        }

        return results.distinctBy {
            it.url
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val contentUrl = normalizeUrl(
            attr("href")
        )

        if (!isValidContentUrl(contentUrl)) {
            return null
        }

        val parent = parent()
        val container = parent ?: this

        val image = container.select(
            "img"
        ).firstOrNull()

        val title = firstNonBlank(
            attr("title"),
            attr("aria-label"),
            container.select("h1").firstOrNull()?.text(),
            container.select("h2").firstOrNull()?.text(),
            container.select("h3").firstOrNull()?.text(),
            container.select(".title").firstOrNull()?.text(),
            container.select(".film-title").firstOrNull()?.text(),
            container.select(".movie-title").firstOrNull()?.text(),
            text(),
            image?.attr("alt")
        )?.trim()

        if (title.isNullOrBlank()) {
            return null
        }

        if (title.length < 2) {
            return null
        }

        val posterUrl = image?.let {

            val raw = firstNonBlank(
                it.attr("data-src"),
                it.attr("data-lazy-src"),
                it.attr("data-original"),
                it.attr("src")
            )

            raw?.let {
                normalizeUrl(it)
            }
        }

        val type =
            if (isSeriesUrl(contentUrl)) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

        return newMovieSearchResponse(
            title,
            contentUrl,
            type
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val pageUrl = normalizeUrl(url)

        return try {

            val document = app.get(
                pageUrl,
                headers = defaultHeaders
            ).document

            val title = firstNonBlank(
                document.select("h1").firstOrNull()?.text(),
                document.select("h2").firstOrNull()?.text(),
                document.select("meta[property=og:title]")
                    .firstOrNull()
                    ?.attr("content"),
                document.title()
            )?.trim() ?: "Bilinmeyen içerik"

            val poster = findPoster(document)

            val plot = firstNonBlank(
                document.select("meta[name=description]")
                    .firstOrNull()
                    ?.attr("content"),

                document.select("meta[property=og:description]")
                    .firstOrNull()
                    ?.attr("content"),

                document.select(".description")
                    .firstOrNull()
                    ?.text(),

                document.select(".overview")
                    .firstOrNull()
                    ?.text(),

                document.select(".story")
                    .firstOrNull()
                    ?.text(),

                document.select(".post-content")
                    .firstOrNull()
                    ?.text()
            )?.trim()

            if (isSeriesUrl(pageUrl)) {

                val episodes = parseEpisodes(document)

                newTvSeriesLoadResponse(
                    title,
                    pageUrl,
                    TvType.TvSeries,
                    episodes
                ) {
                    this.posterUrl = poster
                    this.plot = plot
                }

            } else {

                newMovieLoadResponse(
                    title,
                    pageUrl,
                    TvType.Movie,
                    pageUrl
                ) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }

        } catch (e: Exception) {

            logError(
                "Detay sayfası yüklenemedi: $pageUrl",
                e
            )

            newMovieLoadResponse(
                "İçerik yüklenemedi",
                pageUrl,
                TvType.Movie,
                pageUrl
            )
        }
    }

    private fun findPoster(
        document: Document
    ): String? {

        val element = document.select(
            "meta[property=og:image]," +
                "meta[name=twitter:image]," +
                ".poster img," +
                ".movie-poster img," +
                ".film-poster img," +
                "article img"
        ).firstOrNull()

        if (element == null) {
            return null
        }

        val raw = firstNonBlank(
            element.attr("content"),
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("src")
        )

        return raw?.let {
            normalizeUrl(it)
        }
    }

    private fun parseEpisodes(
        document: Document
    ): List<Episode> {

        val episodes = mutableListOf<Episode>()

        val elements = document.select(
            ".episode-list a[href]," +
                ".episodes a[href]," +
                ".episode a[href]," +
                ".season-list a[href]," +
                ".seasons-list a[href]," +
                "a[href*='/bolum-']," +
                "a[href*='/bolum/']," +
                "a[href*='-bolum-']"
        )

        for (element in elements) {

            val episodeUrl = normalizeUrl(
                element.attr("href")
            )

            if (!isValidContentUrl(episodeUrl)) {
                continue
            }

            val episodeName = firstNonBlank(
                element.text(),
                element.attr("title"),
                element.attr("aria-label")
            )?.trim()

            if (episodeName.isNullOrBlank()) {
                continue
            }

            episodes.add(
                newEpisode(episodeUrl) {
                    this.name = episodeName
                }
            )
        }

        return episodes.distinctBy {
            it.data
        }
    }

    // ============================================================
    // VIDEO EXTRACTION
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl = normalizeUrl(data)

        try {

            val response = app.get(
                pageUrl,
                headers = defaultHeaders
            )

            val document = response.document

            /*
             * Öncelik:
             *
             * 1. /rplayer/ iframe
             * 2. Rapidrame iframe
             * 3. diğer iframe'ler
             */

            val iframeElements = document.select(
                "iframe[src], iframe[data-src]"
            )

            val iframeUrls = linkedSetOf<String>()

            /*
             * Önce rplayer.
             */
            for (iframe in iframeElements) {

                val src = firstNonBlank(
                    iframe.attr("src"),
                    iframe.attr("data-src")
                ) ?: continue

                if (
                    src.contains("/rplayer/", ignoreCase = true)
                ) {
                    iframeUrls.add(
                        normalizeUrl(src)
                    )
                }
            }

            /*
             * Sonra diğer iframe'ler.
             */
            for (iframe in iframeElements) {

                val src = firstNonBlank(
                    iframe.attr("src"),
                    iframe.attr("data-src")
                ) ?: continue

                iframeUrls.add(
                    normalizeUrl(src)
                )
            }

            /*
             * HTML içinde doğrudan M3U8 varsa
             * onu da deneyelim.
             */
            val directM3u8Regex = Regex(
                """https?://[^"'<>\\\s]+\.m3u8[^"'<>\\\s]*"""
            )

            for (match in directM3u8Regex.findAll(response.text)) {

                val directUrl = match.value
                    .replace("\\/", "/")
                    .replace("&amp;", "&")

                try {

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Rapidrame",
                            url = directUrl,
                            type = ExtractorLinkType.M3U8
                        ) {

                            referer = pageUrl

                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to pageUrl
                            )

                            quality = Qualities.Unknown.value
                        }
                    )

                    return true

                } catch (e: Exception) {

                    logError(
                        "Doğrudan M3U8 eklenemedi",
                        e
                    )
                }
            }

            if (iframeUrls.isEmpty()) {

                logError(
                    "Iframe bulunamadı: $pageUrl",
                    IllegalStateException("No iframe")
                )

                return false
            }

            /*
             * Her iframe'i deniyoruz.
             */
            for (iframeUrl in iframeUrls) {

                try {

                    logError(
                        "Iframe deneniyor: $iframeUrl",
                        Exception("debug")
                    )

                    val videoUrl = extractPackedVideoUrl(
                        iframeUrl
                    )

                    if (videoUrl.isNullOrBlank()) {
                        continue
                    }

                    if (!isVideoUrl(videoUrl)) {
                        continue
                    }

                    val origin = getOrigin(
                        iframeUrl
                    )

                    /*
                     * Stremio scraper'ın yaptığı gibi
                     * iframe origin'ini Referer/Origin olarak
                     * kullanıyoruz.
                     */
                    val streamHeaders = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$origin/",
                        "Origin" to origin
                    )

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Rapidrame",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {

                            referer = "$origin/"

                            headers = streamHeaders

                            quality = Qualities.Unknown.value
                        }
                    )

                    logError(
                        "M3U8 başarıyla çıkarıldı",
                        Exception(videoUrl)
                    )

                    return true

                } catch (e: Exception) {

                    logError(
                        "Iframe çözülemedi: $iframeUrl",
                        e
                    )
                }
            }

        } catch (e: Exception) {

            logError(
                "Video sayfası okunamadı: $pageUrl",
                e
            )
        }

        return false
    }

    // ============================================================
    // HDFILMCEHENNEMI PACKED JS DECODER
    // ============================================================

    private suspend fun extractPackedVideoUrl(
        iframeUrl: String
    ): String? {

        val iframeHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to "$mainUrl/"
        )

        val html = app.get(
            iframeUrl,
            headers = iframeHeaders
        ).text

        /*
         * Eğer sayfa zaten doğrudan m3u8 içeriyorsa.
         */
        val directRegex = Regex(
            """https?://[^"'<>\\\s]+\.m3u8[^"'<>\\\s]*"""
        )

        val direct = directRegex
            .findAll(html)
            .map {
                it.value
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
            }
            .firstOrNull()

        if (!direct.isNullOrBlank()) {
            return direct
        }

        /*
         * JavaScript Packer:
         *
         * eval(function(p,a,c,k,e,d){...}
         * ('...',62,123,'a|b|c|...')
         */
        val packedRegex = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+)',(\d+),(\d+),'([^']+)'""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        val packedMatch = packedRegex.find(
            html
        ) ?: return null

        val packedCode = packedMatch.groupValues[1]

        val base = packedMatch.groupValues[2]
            .toIntOrNull()
            ?: return null

        val count = packedMatch.groupValues[3]
            .toIntOrNull()
            ?: return null

        val keywords = packedMatch.groupValues[4]

        val unpacked = unpackJs(
            packedCode,
            base,
            count,
            keywords
        )

        /*
         * Beklenen yapı:
         *
         * dc_xxx(["...", "...", "..."])
         */
        val partsRegex = Regex(
            """dc_\w+\(\[([^\]]+)\]\)"""
        )

        val partsMatch = partsRegex.find(
            unpacked
        ) ?: return null

        val partsString = partsMatch.groupValues[1]

        val partRegex = Regex(
            """"([^"]*)""""
        )

        val parts = partRegex
            .findAll(partsString)
            .map {
                it.groupValues[1]
            }
            .toList()

        if (parts.isEmpty()) {
            return null
        }

        return decodeVideoUrl(
            parts
        )
    }

    private fun unpackJs(
        packed: String,
        base: Int,
        count: Int,
        keywordString: String
    ): String {

        val keywords = keywordString.split("|")

        fun decodeWord(
            word: String
        ): String {

            var number = 0

            for (char in word) {

                number *= base

                number += when {

                    char.isDigit() ->
                        char.digitToInt()

                    char in 'a'..'z' ->
                        char.code - 'a'.code + 10

                    char in 'A'..'Z' ->
                        char.code - 'A'.code + 36

                    else ->
                        0
                }
            }

            return if (
                number < keywords.size &&
                keywords[number].isNotEmpty()
            ) {
                keywords[number]
            } else {
                word
            }
        }

        val wordRegex = Regex(
            """\b\w+\b"""
        )

        return wordRegex.replace(
            packed
        ) {
            decodeWord(
                it.value
            )
        }
    }

    private fun decodeVideoUrl(
        parts: List<String>
    ): String? {

        val value = parts.joinToString("")

        /*
         * Scraper'ın Variant 3'ü:
         *
         * Base64
         * ↓
         * reverse
         * ↓
         * ROT13
         * ↓
         * characterUnmix
         */
        try {

            val result3 = decodeVariant3(
                value
            )

            if (isVideoUrl(result3)) {
                return result3
            }

        } catch (e: Exception) {

            logError(
                "Decode Variant 3 başarısız",
                e
            )
        }

        /*
         * Variant 1:
         *
         * reverse
         * ↓
         * ROT13
         * ↓
         * Base64
         * ↓
         * unmix
         */
        try {

            val reversed = value
                .reversed()

            val result1 = decodeVariant1(
                reversed
            )

            if (isVideoUrl(result1)) {
                return result1
            }

        } catch (e: Exception) {

            logError(
                "Decode Variant 1 başarısız",
                e
            )
        }

        /*
         * Variant 2:
         *
         * reverse
         * ↓
         * Base64
         * ↓
         * ROT13
         * ↓
         * unmix
         */
        try {

            val reversed = value
                .reversed()

            val result2 = decodeVariant2(
                reversed
            )

            if (isVideoUrl(result2)) {
                return result2
            }

        } catch (e: Exception) {

            logError(
                "Decode Variant 2 başarısız",
                e
            )
        }

        return null
    }

    private fun decodeVariant1(
        reversed: String
    ): String {

        var value = rot13(
            reversed
        )

        value = base64Latin1Decode(
            value
        )

        return characterUnmix(
            value
        )
    }

    private fun decodeVariant2(
        reversed: String
    ): String {

        var value = base64Latin1Decode(
            reversed
        )

        value = rot13(
            value
        )

        return characterUnmix(
            value
        )
    }

    private fun decodeVariant3(
        value: String
    ): String {

        var result = base64Latin1Decode(
            value
        )

        result = result
            .reversed()

        result = rot13(
            result
        )

        return characterUnmix(
            result
        )
    }

    private fun base64Latin1Decode(
        value: String
    ): String {

        val bytes = Base64
            .getDecoder()
            .decode(value)

        /*
         * JavaScript:
         *
         * Buffer.from(value, 'base64')
         *     .toString('latin1')
         *
         * karşılığı.
         */
        return String(
            bytes,
            Charset.forName("ISO-8859-1")
        )
    }

    private fun rot13(
        value: String
    ): String {

        val output = StringBuilder(
            value.length
        )

        for (char in value) {

            val code = char.code

            val decoded = when {

                code in 'a'.code..'z'.code ->
                    (
                        'a'.code +
                            (code - 'a'.code + 13) % 26
                        ).toChar()

                code in 'A'.code..'Z'.code ->
                    (
                        'A'.code +
                            (code - 'A'.code + 13) % 26
                        ).toChar()

                else ->
                    char
            }

            output.append(
                decoded
            )
        }

        return output.toString()
    }

    private fun characterUnmix(
        value: String
    ): String {

        val output = StringBuilder(
            value.length
        )

        val magicNumber = 399756995L

        for (i in value.indices) {

            val charCode =
                value[i].code

            val subtract =
                (
                    magicNumber %
                        (i + 5L)
                    ).toInt()

            val decoded =
                (
                    charCode -
                        subtract +
                        256
                    ) % 256

            output.append(
                decoded.toChar()
            )
        }

        return output.toString()
    }

    private fun isVideoUrl(
        url: String?
    ): Boolean {

        if (url.isNullOrBlank()) {
            return false
        }

        if (!url.startsWith("https://")) {
            return false
        }

        val lower = url.lowercase()

        return lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            lower.contains(".mp4")
    }

    private fun getOrigin(
        url: String
    ): String {

        return try {

            val match = Regex(
                """^(https?://[^/]+)"""
            ).find(url)

            match?.groupValues?.get(1)
                ?: mainUrl

        } catch (_: Exception) {
            mainUrl
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private fun normalizeUrl(
        rawUrl: String?
    ): String {

        if (rawUrl.isNullOrBlank()) {
            return ""
        }

        val value = rawUrl
            .trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return when {

            value.startsWith("//") ->
                "https:$value"

            value.startsWith("http://") ||
                value.startsWith("https://") ->
                value

            value.startsWith("/") ->
                "$mainUrl$value"

            else ->
                "$mainUrl/$value"
        }
    }

    private fun isSeriesUrl(
        url: String
    ): Boolean {

        val lower = url.lowercase()

        return lower.contains("/dizi/") ||
            lower.contains("/series/") ||
            lower.contains("/tv/") ||
            lower.contains("/sezon-")
    }

    private fun isValidContentUrl(
        url: String
    ): Boolean {

        if (url.isBlank()) {
            return false
        }

        if (!url.startsWith("$mainUrl/")) {
            return false
        }

        val lower = url.lowercase()

        val ignored = listOf(
            "/category/",
            "/kategori/",
            "/tur/",
            "/imdb/",
            "/sayfa/",
            "/page/",
            "/iletisim/",
            "/yardim/",
            "/login/",
            "/register/",
            "/uye/",
            "/etiket/",
            "/tag/"
        )

        if (
            ignored.any {
                lower.contains(it)
            }
        ) {
            return false
        }

        return true
    }

    private fun firstNonBlank(
        vararg values: String?
    ): String? {

        return values.firstOrNull {
            !it.isNullOrBlank()
        }
    }

    private fun logError(
        message: String,
        error: Throwable
    ) {

        println(
            "HDFilmCehennemi: $message | " +
                "${error::class.simpleName}: ${error.message}"
        )
    }
}
