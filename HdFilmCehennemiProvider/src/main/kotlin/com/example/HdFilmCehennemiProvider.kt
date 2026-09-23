package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
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
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/film-izle-2/" to "Son Eklenenler",
        "$mainUrl/category/nette-ilk-filmler-1/" to "Nette İlk",
        "$mainUrl/category/tavsiye-filmler-izle3/" to "Tavsiye Filmler",
        "$mainUrl/tur/aksiyon-filmleri-izleyin-8/" to "Aksiyon",
        "$mainUrl/tur/komedi-filmlerini-izleyin-2/" to "Komedi",
        "$mainUrl/category/1080p-hd-film-izle-5/" to "1080p"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val currentPage: Int =
            page.coerceAtLeast(1)

        val baseUrl =
            normalizeUrl(request.data)

        val pageUrl: String =
            if (currentPage <= 1) {
                baseUrl
            } else {
                if (baseUrl.endsWith("/")) {
                    "$baseUrl?page=$currentPage"
                } else {
                    "$baseUrl/?page=$currentPage"
                }
            }

        return try {

            val document: Document =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            val results: List<SearchResponse> =
                parseResults(document)

            newHomePageResponse(
                listOf(
                    HomePageList(
                        request.name,
                        results,
                        isHorizontalImages = true
                    )
                ),
                hasNext = results.isNotEmpty()
            )

        } catch (error: Exception) {

            logError(
                "Ana sayfa yüklenemedi: $pageUrl",
                error
            )

            newHomePageResponse(
                emptyList(),
                hasNext = false
            )
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery: String =
            URLEncoder
                .encode(
                    query.trim(),
                    "UTF-8"
                )
                .replace(
                    "+",
                    "%20"
                )

        val searchUrls: List<String> =
            listOf(
                "$mainUrl/search/$encodedQuery/",
                "$mainUrl/?s=$encodedQuery"
            )

        for (searchUrl: String in searchUrls) {

            try {

                val document: Document =
                    app.get(
                        searchUrl,
                        headers = headers
                    ).document

                val results: List<SearchResponse> =
                    parseResults(document)

                if (results.isNotEmpty()) {
                    return results
                }

            } catch (error: Exception) {

                logError(
                    "Arama başarısız: $searchUrl",
                    error
                )
            }
        }

        return emptyList()
    }

    private fun parseResults(
        document: Document
    ): List<SearchResponse> {

        val results:
            MutableList<SearchResponse> =
            mutableListOf()

        val posterElements: Elements =
            document.select(
                "a.poster[href]"
            )

        for (element: Element in posterElements) {

            val result:
                SearchResponse? =
                element.toSearchResult()

            if (result != null) {
                results.add(result)
            }
        }

        if (results.isEmpty()) {

            val fallbackSelector: String =
                "article," +
                    ".movie-box," +
                    ".movie-item," +
                    ".film-box," +
                    ".film-item," +
                    ".card"

            val fallbackElements: Elements =
                document.select(
                    fallbackSelector
                )

            for (
                element: Element
                in fallbackElements
            ) {

                val result:
                    SearchResponse? =
                    element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }
        }

        return results.distinctBy {
            result: SearchResponse ->
            result.url
        }
    }

    private fun Element.toSearchResult():
        SearchResponse? {

        val linkElement: Element? =
            if (
                tagName() == "a" &&
                hasAttr("href")
            ) {
                this
            } else {
                findElement(
                    this,
                    "a.poster[href],a[href]"
                )
            }

        if (linkElement == null) {
            return null
        }

        val contentUrl: String =
            normalizeUrl(
                linkElement.attr("href")
            )

        if (!isValidContentUrl(contentUrl)) {
            return null
        }

        val imageElement: Element? =
            findElement(
                this,
                "img"
            )

        val title: String? =
            firstNonBlank(
                linkElement.attr("title"),
                findElement(
                    this,
                    "h1"
                )?.text(),
                findElement(
                    this,
                    "h2"
                )?.text(),
                findElement(
                    this,
                    "h3"
                )?.text(),
                findElement(
                    this,
                    ".title"
                )?.text(),
                findElement(
                    this,
                    ".film-title"
                )?.text(),
                findElement(
                    this,
                    ".movie-title"
                )?.text(),
                imageElement?.attr("alt")
            )?.trim()

        if (title.isNullOrBlank()) {
            return null
        }

        val posterUrl: String? =
            imageElement?.let { image: Element ->

                val rawPoster: String? =
                    firstNonBlank(
                        image.attr("data-src"),
                        image.attr("data-lazy-src"),
                        image.attr("data-original"),
                        image.attr("src")
                    )

                if (rawPoster.isNullOrBlank()) {
                    null
                } else {
                    normalizeUrl(rawPoster)
                }
            }

        val type: TvType =
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

        val pageUrl: String =
            normalizeUrl(url)

        return try {

            val document: Document =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            val title: String =
                firstNonBlank(
                    findElement(
                        document,
                        "h1"
                    )?.text(),
                    findElement(
                        document,
                        "h2"
                    )?.text(),
                    findElement(
                        document,
                        "meta[property=og:title]"
                    )?.attr("content"),
                    document.title()
                )?.trim()
                    ?: "Bilinmeyen içerik"

            val posterElement: Element? =
                findElement(
                    document,
                    "meta[property=og:image]," +
                        "div.poster img," +
                        ".poster img," +
                        ".movie-poster img," +
                        "article img"
                )

            val poster: String? =
                if (posterElement == null) {

                    null

                } else {

                    val rawPoster: String? =
                        firstNonBlank(
                            posterElement.attr("content"),
                            posterElement.attr("data-src"),
                            posterElement.attr("data-lazy-src"),
                            posterElement.attr("data-original"),
                            posterElement.attr("src")
                        )

                    if (rawPoster.isNullOrBlank()) {
                        null
                    } else {
                        normalizeUrl(rawPoster)
                    }
                }

            val plot: String? =
                firstNonBlank(
                    findElement(
                        document,
                        "meta[name=description]"
                    )?.attr("content"),

                    findElement(
                        document,
                        "meta[property=og:description]"
                    )?.attr("content"),

                    findElement(
                        document,
                        ".description"
                    )?.text(),

                    findElement(
                        document,
                        ".overview"
                    )?.text(),

                    findElement(
                        document,
                        ".story"
                    )?.text(),

                    findElement(
                        document,
                        ".post-content"
                    )?.text()
                )?.trim()

            if (isSeriesUrl(pageUrl)) {

                val episodes: List<Episode> =
                    parseEpisodes(document)

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

        } catch (error: Exception) {

            logError(
                "Detay sayfası yüklenemedi: $pageUrl",
                error
            )

            newMovieLoadResponse(
                "İçerik yüklenemedi",
                pageUrl,
                TvType.Movie,
                pageUrl
            )
        }
    }

    private fun parseEpisodes(
        document: Document
    ): List<Episode> {

        val selector: String =
            ".episode-list a[href]," +
                ".episodes a[href]," +
                "ul.episodes a[href]," +
                ".season-list a[href]," +
                ".seasons-list a[href]," +
                "a[href*='/bolum']," +
                "a[href*='-bolum-']"

        val elements: Elements =
            document.select(selector)

        val episodes:
            MutableList<Episode> =
            mutableListOf()

        for (element: Element in elements) {

            val episodeUrl: String =
                normalizeUrl(
                    element.attr("href")
                )

            if (!isValidContentUrl(episodeUrl)) {
                continue
            }

            val episodeName: String? =
                firstNonBlank(
                    element.text(),
                    element.attr("title"),
                    element.attr("aria-label")
                )?.trim()

            if (episodeName.isNullOrBlank()) {
                continue
            }

            episodes.add(
                newEpisode(episodeUrl) {
                    name = episodeName
                }
            )
        }

        return episodes.distinctBy {
            episode: Episode ->
            episode.data
        }
    }

    /*
     * ============================================================
     * DİNAMİK RAPIDRAME LOADLINKS
     * ============================================================
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl =
            normalizeUrl(data)

        return try {

            /*
             * Film detay sayfasını al.
             */
            val pageResponse =
                app.get(
                    pageUrl,
                    headers = headers
                )

            val pageDocument =
                pageResponse.document

            /*
             * Sayfadaki iframe'i bul.
             *
             * Önce src,
             * yoksa data-src.
             */
            val iframeElement =
                pageDocument
                    .select("iframe")
                    .firstOrNull()

            if (iframeElement == null) {

                logError(
                    "Video iframe bulunamadı: $pageUrl",
                    IllegalStateException(
                        "iframe yok"
                    )
                )

                return false
            }

            val iframeSrc =
                firstNonBlank(
                    iframeElement.attr("src"),
                    iframeElement.attr("data-src")
                )

            if (iframeSrc.isNullOrBlank()) {

                logError(
                    "Iframe URL boş: $pageUrl",
                    IllegalStateException(
                        "iframe src boş"
                    )
                )

                return false
            }

            val normalizedIframeUrl =
                normalizeUrl(
                    iframeSrc
                )

            /*
             * alternative-link kaynaklarını topla.
             *
             * Örnek:
             *
             * <button
             *   class="alternative-link"
             *   data-video="322665">
             *   Rapidrame
             * </button>
             */
            data class AlternativeSource(
                val name: String,
                val videoId: String,
                val active: Boolean
            )

            val alternatives =
                pageDocument
                    .select(
                        ".alternative-link"
                    )
                    .mapNotNull { element ->

                        val sourceName =
                            element.text()
                                .trim()

                        val videoId =
                            element
                                .attr("data-video")
                                .trim()

                        val active =
                            element
                                .attr("data-active")
                                .trim() == "1"

                        if (
                            videoId.isNotBlank()
                        ) {
                            AlternativeSource(
                                name = sourceName,
                                videoId = videoId,
                                active = active
                            )
                        } else {
                            null
                        }
                    }

            /*
             * ====================================================
             * Yardımcı fonksiyonlar
             * ====================================================
             */

            fun rot13(
                value: String
            ): String {

                return buildString {

                    for (character in value) {

                        when {

                            character in 'a'..'z' -> {

                                append(
                                    (
                                        (
                                            character.code -
                                                'a'.code +
                                                13
                                            ) % 26 +
                                            'a'.code
                                        ).toChar()
                                )
                            }

                            character in 'A'..'Z' -> {

                                append(
                                    (
                                        (
                                            character.code -
                                                'A'.code +
                                                13
                                            ) % 26 +
                                            'A'.code
                                        ).toChar()
                                )
                            }

                            else -> {
                                append(character)
                            }
                        }
                    }
                }
            }

            fun characterUnmix(
                value: String
            ): String {

                val output =
                    StringBuilder()

                for (
                    i in value.indices
                ) {

                    val charCode =
                        value[i].code

                    val mixed =
                        (
                            charCode -
                                (
                                    399756995L %
                                        (i + 5)
                                    ) +
                                256
                            ) % 256

                    output.append(
                        mixed.toInt().toChar()
                    )
                }

                return output.toString()
            }

            fun base64Decode(
                value: String
            ): String {

                val decoded =
                    android.util.Base64.decode(
                        value,
                        android.util.Base64.DEFAULT
                    )

                return decoded.toString(
                    Charsets.ISO_8859_1
                )
            }

            fun isValidVideoUrl(
                url: String?
            ): Boolean {

                if (url.isNullOrBlank()) {
                    return false
                }

                val value =
                    url.trim()

                return value.startsWith(
                    "https://"
                ) &&
                    (
                        value.contains(
                            ".m3u8"
                        ) ||
                            value.contains(
                                "/hls/"
                            ) ||
                            value.contains(
                                ".mp4"
                            )
                        )
            }

            /*
             * ====================================================
             * JavaScript Packer
             * ====================================================
             *
             * Stremio scraper'daki unpackJS
             * algoritmasının Kotlin karşılığı.
             */
            fun unpackJs(
                packedCode: String,
                base: Int,
                keywords: String
            ): String {

                val keywordList =
                    keywords.split("|")

                fun decode(
                    word: String
                ): String {

                    var number = 0

                    for (
                        character in word
                    ) {

                        when {

                            character in '0'..'9' -> {

                                number =
                                    number * base +
                                        (
                                            character.code -
                                                '0'.code
                                            )
                            }

                            character in 'a'..'z' -> {

                                number =
                                    number * base +
                                        (
                                            character.code -
                                                'a'.code +
                                                10
                                            )
                            }

                            character in 'A'..'Z' -> {

                                number =
                                    number * base +
                                        (
                                            character.code -
                                                'A'.code +
                                                36
                                            )
                            }
                        }
                    }

                    return if (
                        number >= 0 &&
                        number < keywordList.size &&
                        keywordList[number]
                            .isNotEmpty()
                    ) {
                        keywordList[number]
                    } else {
                        word
                    }
                }

                return Regex(
                    """\b\w+\b"""
                ).replace(
                    packedCode
                ) { match ->

                    decode(
                        match.value
                    )
                }
            }

            /*
             * ====================================================
             * Video URL Decoder
             * ====================================================
             */

            fun decodeVariant1(
                value: String
            ): String? {

                return try {

                    /*
                     * join
                     * ↓
                     * reverse
                     * ↓
                     * ROT13
                     * ↓
                     * Base64
                     * ↓
                     * unmix
                     */
                    var result =
                        value.reversed()

                    result =
                        rot13(result)

                    result =
                        base64Decode(result)

                    result =
                        characterUnmix(result)

                    if (
                        isValidVideoUrl(
                            result
                        )
                    ) {
                        result.trim()
                    } else {
                        null
                    }

                } catch (
                    error: Exception
                ) {

                    null
                }
            }

            fun decodeVariant2(
                value: String
            ): String? {

                return try {

                    /*
                     * join
                     * ↓
                     * reverse
                     * ↓
                     * Base64
                     * ↓
                     * ROT13
                     * ↓
                     * unmix
                     */
                    var result =
                        value.reversed()

                    result =
                        base64Decode(result)

                    result =
                        rot13(result)

                    result =
                        characterUnmix(result)

                    if (
                        isValidVideoUrl(
                            result
                        )
                    ) {
                        result.trim()
                    } else {
                        null
                    }

                } catch (
                    error: Exception
                ) {

                    null
                }
            }

            fun decodeVariant3(
                value: String
            ): String? {

                return try {

                    /*
                     * Güncel algoritma:
                     *
                     * join
                     * ↓
                     * Base64
                     * ↓
                     * reverse
                     * ↓
                     * ROT13
                     * ↓
                     * unmix
                     */
                    var result =
                        base64Decode(value)

                    result =
                        result.reversed()

                    result =
                        rot13(result)

                    result =
                        characterUnmix(result)

                    if (
                        isValidVideoUrl(
                            result
                        )
                    ) {
                        result.trim()
                    } else {
                        null
                    }

                } catch (
                    error: Exception
                ) {

                    null
                }
            }

            fun decodeVideoUrl(
                parts: List<String>
            ): String? {

                val joined =
                    parts.joinToString("")

                /*
                 * Güncel algoritmayı önce dene.
                 */
                val variant3 =
                    decodeVariant3(
                        joined
                    )

                if (
                    variant3 != null
                ) {
                    return variant3
                }

                /*
                 * Eski varyant 1.
                 */
                val variant1 =
                    decodeVariant1(
                        joined
                    )

                if (
                    variant1 != null
                ) {
                    return variant1
                }

                /*
                 * Eski varyant 2.
                 */
                val variant2 =
                    decodeVariant2(
                        joined
                    )

                if (
                    variant2 != null
                ) {
                    return variant2
                }

                return null
            }

            /*
             * ====================================================
             * iframe çözme fonksiyonu
             * ====================================================
             */

            suspend fun scrapeIframe(
                iframeUrl: String
            ): Pair<String, String>? {

                try {

                    val iframeResponse =
                        app.get(
                            iframeUrl,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Accept" to
                                    "text/html,application/xhtml+xml," +
                                    "application/xml;q=0.9,*/*;q=0.8",
                                "Accept-Language" to
                                    "tr-TR,tr;q=0.9,en;q=0.8",
                                "Referer" to pageUrl
                            )
                        )

                    val html =
                        iframeResponse.text

                    if (html.isBlank()) {
                        return null
                    }

                    /*
                     * =================================================
                     * Packed JS bul
                     * =================================================
                     *
                     * Kaynaktaki regex'in Kotlin karşılığı:
                     *
                     * eval(function(p,a,c,k,e,d){...}
                     * ('...',62,...,'...')
                     */
                    val packedRegex =
                        Regex(
                            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+)',(\d+),(\d+),'([^']+)'""",
                            setOf(
                                RegexOption.DOT_MATCHES_ALL
                            )
                        )

                    val packedMatch =
                        packedRegex.find(
                            html
                        )

                    if (packedMatch != null) {

                        val packedCode =
                            packedMatch.groupValues[1]

                        val base =
                            packedMatch
                                .groupValues[2]
                                .toInt()

                        val count =
                            packedMatch
                                .groupValues[3]
                                .toInt()

                        val keywords =
                            packedMatch
                                .groupValues[4]

                        /*
                         * count değeri JS packer'dan geliyor.
                         * unpack işleminin kendisi keyword listesine
                         * göre çalışıyor.
                         */
                        @Suppress("UNUSED_VARIABLE")
                        val unusedCount =
                            count

                        val decodedJs =
                            unpackJs(
                                packedCode,
                                base,
                                keywords
                            )

                        /*
                         * dc_xxx([
                         *   "...",
                         *   "...",
                         *   "..."
                         * ])
                         */
                        val partsRegex =
                            Regex(
                                """dc_\w+\(\[([^\]]+)\]\)"""
                            )

                        val partsMatch =
                            partsRegex.find(
                                decodedJs
                            )

                        if (partsMatch != null) {

                            val arrayContent =
                                partsMatch
                                    .groupValues[1]

                            /*
                             * Stremio scraper'daki:
                             *
                             * /"([^"]+)"/g
                             */
                            val stringRegex =
                                Regex(
                                    """"([^"]+)""""
                                )

                            val parts =
                                stringRegex
                                    .findAll(
                                        arrayContent
                                    )
                                    .map {
                                        it.groupValues[1]
                                    }
                                    .toList()

                            if (
                                parts.isNotEmpty()
                            ) {

                                val videoUrl =
                                    decodeVideoUrl(
                                        parts
                                    )

                                if (
                                    videoUrl != null
                                ) {

                                    val uri =
                                        java.net.URI(
                                            iframeUrl
                                        )

                                    val origin =
                                        if (
                                            !uri.scheme.isNullOrBlank() &&
                                            !uri.host.isNullOrBlank()
                                        ) {
                                            "${uri.scheme}://${uri.host}"
                                        } else {
                                            "https://hdfilmcehennemi.mobi"
                                        }

                                    return Pair(
                                        videoUrl,
                                        origin
                                    )
                                }
                            }
                        }
                    }

                    /*
                     * =================================================
                     * JSON-LD fallback
                     * =================================================
                     */
                    val jsonLdRegex =
                        Regex(
                            """<script[^>]*type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        )

                    val jsonLdMatch =
                        jsonLdRegex.find(
                            html
                        )

                    if (jsonLdMatch != null) {

                        val jsonLd =
                            jsonLdMatch
                                .groupValues[1]
                                .trim()

                        val contentUrlMatch =
                            Regex(
                                """"contentUrl"\s*:\s*"([^"]+)"""",
                                setOf(
                                    RegexOption.IGNORE_CASE
                                )
                            ).find(
                                jsonLd
                            )

                        if (
                            contentUrlMatch != null
                        ) {

                            val contentUrl =
                                contentUrlMatch
                                    .groupValues[1]

                            if (
                                isValidVideoUrl(
                                    contentUrl
                                )
                            ) {

                                val uri =
                                    java.net.URI(
                                        iframeUrl
                                    )

                                val origin =
                                    if (
                                        !uri.scheme.isNullOrBlank() &&
                                        !uri.host.isNullOrBlank()
                                    ) {
                                        "${uri.scheme}://${uri.host}"
                                    } else {
                                        "https://hdfilmcehennemi.mobi"
                                    }

                                return Pair(
                                    contentUrl,
                                    origin
                                )
                            }
                        }
                    }

                    return null

                } catch (
                    error: Exception
                ) {

                    logError(
                        "Iframe çözülemedi: $iframeUrl",
                        error
                    )

                    return null
                }
            }

            /*
             * ====================================================
             * 1. Önce ana iframe'i dene
             * ====================================================
             */

            var result:
                Pair<String, String>? =
                scrapeIframe(
                    normalizedIframeUrl
                )

            /*
             * ====================================================
             * 2. Ana iframe başarısızsa alternatifleri dene
             * ====================================================
             *
             * Örnek:
             *
             * iframe:
             * https://hdfilmcehennemi.mobi/video/embed/OSxZYB25jjD4/
             *
             * Rapidrame:
             * https://hdfilmcehennemi.mobi/video/embed/
             * OSxZYB25jjD4/
             * ?rapidrame_id=jzkqxar12lb8
             */

            if (result == null) {

                val videoIdMatch =
                    Regex(
                        """/embed/([^/?]+)"""
                    ).find(
                        normalizedIframeUrl
                    )

                val videoId =
                    videoIdMatch
                        ?.groupValues
                        ?.getOrNull(1)

                if (
                    !videoId.isNullOrBlank()
                ) {

                    /*
                     * Önce inactive alternatifleri,
                     * özellikle Rapidrame'i deniyoruz.
                     */
                    val orderedAlternatives =
                        alternatives.sortedBy {
                            alternative ->
                            if (
                                alternative.active
                            ) {
                                1
                            } else {
                                0
                            }
                        }

                    for (
                        alternative
                        in orderedAlternatives
                    ) {

                        val alternativeUrl: String

                        if (
                            alternative.name
                                .equals(
                                    "Rapidrame",
                                    ignoreCase = true
                                )
                        ) {

                            alternativeUrl =
                                "https://hdfilmcehennemi.mobi" +
                                    "/video/embed/" +
                                    "$videoId/" +
                                    "?rapidrame_id=" +
                                    alternative.videoId

                        } else {

                            alternativeUrl =
                                "https://hdfilmcehennemi.mobi" +
                                    "/video/embed/" +
                                    "$videoId/"
                        }

                        result =
                            scrapeIframe(
                                alternativeUrl
                            )

                        if (
                            result != null
                        ) {
                            break
                        }
                    }
                }
            }

            /*
             * ====================================================
             * Hiçbir kaynak çözülemediyse
             * ====================================================
             */

            if (result == null) {

                logError(
                    "Video URL çıkarılamadı: $pageUrl",
                    IllegalStateException(
                        "Rapidrame/iframe decoder sonuç üretmedi"
                    )
                )

                return false
            }

            val videoUrl =
                result.first

            val embedOrigin =
                result.second

            val referer =
                "$embedOrigin/"

            /*
             * ====================================================
             * CloudStream'e gerçek M3U8'i gönder
             * ====================================================
             */

            callback(
                newExtractorLink(
                    source = this.name,
                    name = "Rapidrame",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {

                    this.referer =
                        referer

                    quality =
                        Qualities.Unknown.value

                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer,
                        "Origin" to embedOrigin
                    )
                }
            )

            return true

        } catch (error: Exception) {

            logError(
                "Rapidrame bağlantısı alınamadı: $pageUrl",
                error
            )

            return false
        }
    }

    private fun addCandidate(
        candidates: MutableSet<String>,
        rawUrl: String?
    ) {

        if (rawUrl.isNullOrBlank()) {
            return
        }

        val url: String =
            normalizeUrl(rawUrl)

        if (
            url.startsWith("http://") ||
            url.startsWith("https://")
        ) {
            candidates.add(url)
        }
    }

    private fun findElement(
        document: Document,
        selector: String
    ): Element? {

        val elements: Elements =
            document.select(selector)

        return elements.firstOrNull()
    }

    private fun findElement(
        element: Element,
        selector: String
    ): Element? {

        val elements: Elements =
            element.select(selector)

        return elements.firstOrNull()
    }

    private fun normalizeUrl(
        rawUrl: String?
    ): String {

        if (rawUrl.isNullOrBlank()) {
            return ""
        }

        val value: String =
            rawUrl.trim()

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

        return url.contains("/dizi/") ||
            url.contains("/series/") ||
            url.contains("/tv/")
    }

    private fun isValidContentUrl(
        url: String
    ): Boolean {

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

        return url.startsWith(
            "$mainUrl/"
        )
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
                "${error::class.simpleName}: " +
                error.message
        )
    }
}
