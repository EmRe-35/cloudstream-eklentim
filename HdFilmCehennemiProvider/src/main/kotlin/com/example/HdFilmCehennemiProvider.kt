package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLEncoder
import java.net.URI

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

        val currentPage = page.coerceAtLeast(1)
        val baseUrl = normalizeUrl(request.data)

        val pageUrl =
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

            val document =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            val results =
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

        val encodedQuery =
            URLEncoder
                .encode(
                    query.trim(),
                    "UTF-8"
                )
                .replace(
                    "+",
                    "%20"
                )

        val searchUrls =
            listOf(
                "$mainUrl/search/$encodedQuery/",
                "$mainUrl/?s=$encodedQuery"
            )

        for (searchUrl in searchUrls) {

            try {

                val document =
                    app.get(
                        searchUrl,
                        headers = headers
                    ).document

                val results =
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

        val results =
            mutableListOf<SearchResponse>()

        val posterElements =
            document.select(
                "a.poster[href]"
            )

        for (element in posterElements) {

            val result =
                element.toSearchResult()

            if (result != null) {
                results.add(result)
            }
        }

        if (results.isEmpty()) {

            val fallbackSelector =
                "article," +
                    ".movie-box," +
                    ".movie-item," +
                    ".film-box," +
                    ".film-item," +
                    ".card"

            for (
                element in document.select(
                    fallbackSelector
                )
            ) {

                val result =
                    element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }
        }

        return results.distinctBy {
            it.url
        }
    }

    private fun Element.toSearchResult():
        SearchResponse? {

        val linkElement =
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

        val contentUrl =
            normalizeUrl(
                linkElement.attr("href")
            )

        if (!isValidContentUrl(contentUrl)) {
            return null
        }

        val imageElement =
            findElement(
                this,
                "img"
            )

        val title =
            firstNonBlank(
                linkElement.attr("title"),
                findElement(this, "h1")?.text(),
                findElement(this, "h2")?.text(),
                findElement(this, "h3")?.text(),
                findElement(this, ".title")?.text(),
                findElement(this, ".film-title")?.text(),
                findElement(this, ".movie-title")?.text(),
                imageElement?.attr("alt")
            )?.trim()

        if (title.isNullOrBlank()) {
            return null
        }

        val posterUrl =
            imageElement?.let { image ->

                val rawPoster =
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

        val pageUrl =
            normalizeUrl(url)

        return try {

            val document =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            val title =
                firstNonBlank(
                    findElement(document, "h1")?.text(),
                    findElement(document, "h2")?.text(),
                    findElement(
                        document,
                        "meta[property=og:title]"
                    )?.attr("content"),
                    document.title()
                )?.trim()
                    ?: "Bilinmeyen içerik"

            val posterElement =
                findElement(
                    document,
                    "meta[property=og:image]," +
                        "div.poster img," +
                        ".poster img," +
                        ".movie-poster img," +
                        "article img"
                )

            val poster =
                if (posterElement == null) {
                    null
                } else {

                    val rawPoster =
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

            val plot =
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

                val episodes =
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

        val selector =
            ".episode-list a[href]," +
                ".episodes a[href]," +
                "ul.episodes a[href]," +
                ".season-list a[href]," +
                ".seasons-list a[href]," +
                "a[href*='/bolum']," +
                "a[href*='-bolum-']"

        val episodes =
            mutableListOf<Episode>()

        for (
            element in document.select(selector)
        ) {

            val episodeUrl =
                normalizeUrl(
                    element.attr("href")
                )

            if (!isValidContentUrl(episodeUrl)) {
                continue
            }

            val episodeName =
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
            it.data
        }
    }

    /*
     * ============================================================
     * LOAD LINKS
     * ============================================================
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val pageUrl =
                normalizeUrl(data)

            val pageDocument =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            val iframeElements =
                pageDocument.select(
                    "iframe[src], iframe[data-src]"
                )

            if (iframeElements.isEmpty()) {

                println(
                    "HDFilmCehennemi: iframe bulunamadı -> $pageUrl"
                )

                return false
            }

            data class AlternativeSource(
                val name: String,
                val videoId: String?,
                val active: Boolean
            )

            val alternatives =
                pageDocument
                    .select(".alternative-link")
                    .map { element ->

                        AlternativeSource(
                            name =
                                element
                                    .text()
                                    .trim(),

                            videoId =
                                element
                                    .attr("data-video")
                                    .trim()
                                    .ifBlank {
                                        null
                                    },

                            active =
                                element
                                    .attr("data-active")
                                    .trim() == "1"
                        )
                    }

            /*
             * ========================================================
             * ROT-N
             * ========================================================
             */

            fun rotN(
                value: String,
                shift: Int
            ): String {

                val normalizedShift =
                    ((shift % 26) + 26) % 26

                return value.map { c ->

                    when {

                        c in 'a'..'z' -> {
                            (
                                (
                                    c.code -
                                        'a'.code +
                                        normalizedShift
                                    ) % 26 +
                                    'a'.code
                                ).toChar()
                        }

                        c in 'A'..'Z' -> {
                            (
                                (
                                    c.code -
                                        'A'.code +
                                        normalizedShift
                                    ) % 26 +
                                    'A'.code
                                ).toChar()
                        }

                        else -> c
                    }

                }.joinToString("")
            }

            /*
             * ========================================================
             * BASE64
             * ========================================================
             */

            fun base64Decode(
                value: String
            ): String {

                val cleaned =
                    value
                        .replace("\\/", "/")
                        .replace(
                            "\\u002F",
                            "/",
                            ignoreCase = true
                        )
                        .replace(
                            "\\u003D",
                            "=",
                            ignoreCase = true
                        )
                        .trim()

                val bytes =
                    android.util.Base64.decode(
                        cleaned,
                        android.util.Base64.DEFAULT
                    )

                return bytes.toString(
                    Charsets.ISO_8859_1
                )
            }

            /*
             * ========================================================
             * URL TEMİZLEME
             * ========================================================
             */

            fun cleanVideoUrl(
                raw: String?
            ): String? {

                if (raw.isNullOrBlank()) {
                    return null
                }

                return raw
                    .trim()
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
                    .replace("\\u0026", "&")
                    .replace("\\u003D", "=")
                    .replace("\\u002F", "/")
                    .trim(
                        '"',
                        '\'',
                        '`',
                        ' ',
                        '\n',
                        '\r',
                        '\t'
                    )
            }

            /*
             * ========================================================
             * URL KONTROLÜ
             * ========================================================
             */

            fun isValidVideoUrl(
                url: String?
            ): Boolean {

                val value =
                    cleanVideoUrl(url)

                if (value.isNullOrBlank()) {
                    return false
                }

                return (
                    value.startsWith("https://") ||
                        value.startsWith("http://")
                    ) &&
                    (
                        value.contains(
                            ".m3u8",
                            ignoreCase = true
                        ) ||
                            value.contains(
                                "/hls/",
                                ignoreCase = true
                            ) ||
                            value.contains(
                                ".mp4",
                                ignoreCase = true
                            ) ||
                            (
                                value.contains(
                                    "hdfilmcehennemi.download/download/",
                                    ignoreCase = true
                                )
                            )
                        )
            }

            /*
             * ========================================================
             * HERHANGİ BİR M3U8 BUL
             * ========================================================
             */

            fun findVideoUrlInText(
                text: String
            ): String? {

                if (text.isBlank()) {
                    return null
                }

                val normalized =
                    text
                        .replace("\\/", "/")
                        .replace("&amp;", "&")
                        .replace("\\u002F", "/")
                        .replace("\\u0026", "&")
                        .replace("\\u003D", "=")

                val patterns =
                    listOf(

                        Regex(
                            """https?://[^"'`<>\s]+\.m3u8(?:\?[^"'`<>\s]*)?""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        ),

                        Regex(
                            """(?:file|src|url|source)\s*[:=]\s*["'](https?://[^"'\\]+\.m3u8(?:\?[^"'\\]*)?)["']""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        ),

                        Regex(
                            """(https?://[^"'`\s<>]+\.m3u8[^"'`\s<>]*)""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        )
                    )

                for (pattern in patterns) {

                    val match =
                        pattern
                            .find(normalized)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?: pattern
                                .find(normalized)
                                ?.value

                    val cleaned =
                        cleanVideoUrl(match)

                    if (isValidVideoUrl(cleaned)) {
                        return cleaned
                    }
                }

                return null
            }

            /*
             * ========================================================
             * CHARACTER UNMIX
             * ========================================================
             */

            fun characterUnmix(
                value: String,
                magic: Long = 399756995L,
                offset: Int = 5
            ): String {

                val output =
                    StringBuilder()

                for (i in value.indices) {

                    val charCode =
                        value[i].code

                    val shift =
                        (
                            magic %
                                (i + offset).toLong()
                            ).toInt()

                    val unmixed =
                        (
                            charCode -
                                shift +
                                256
                            ) % 256

                    output.append(
                        unmixed.toChar()
                    )
                }

                return output.toString()
            }

            /*
             * ========================================================
             * ROLLING XOR
             *
             * Güncel decoder formatında kullanılıyor.
             * ========================================================
             */

            fun rollingXor(
                value: String,
                seed: Int,
                increment: Int
            ): String {

                var accumulator =
                    seed

                val output =
                    StringBuilder()

                for (char in value) {

                    val byte =
                        char.code

                    accumulator =
                        (
                            accumulator +
                                increment
                            ) % 256

                    output.append(
                        (
                            byte xor
                                accumulator
                            ).toChar()
                    )

                    accumulator =
                        (
                            accumulator +
                                byte
                            ) % 256
                }

                return output.toString()
            }

            /*
             * ========================================================
             * INLINE DECODER
             * ========================================================
             */

            data class DecodeStep(
                val type: String,
                val value1: Int = 0,
                val value2: Int = 0
            )

            data class InlineDecoder(
                val steps: List<DecodeStep>,
                val parts: List<String>
            )

            /*
             * Fonksiyon gövdesini dengeli süslü parantezlerle bul.
             * Böylece decoder içerisinde for/if blokları olduğunda
             * regex'in erken bitmesini engelliyoruz.
             */
            fun extractFunctionBody(
                html: String,
                functionStart: Int
            ): String? {

                val openBrace =
                    html.indexOf(
                        '{',
                        functionStart
                    )

                if (openBrace < 0) {
                    return null
                }

                var depth = 0

                for (
                    i in openBrace until html.length
                ) {

                    when (html[i]) {

                        '{' -> {
                            depth++
                        }

                        '}' -> {

                            depth--

                            if (depth == 0) {

                                return html.substring(
                                    openBrace + 1,
                                    i
                                )
                            }
                        }
                    }
                }

                return null
            }

            fun parseInlineDecoders(
                html: String
            ): List<InlineDecoder> {

                val decoders =
                    mutableListOf<InlineDecoder>()

                val functionRegex =
                    Regex(
                        """function\s+(dc_\w+)\s*\(\s*[\w$]+\s*\)\s*\{"""
                    )

                for (
                    functionMatch in
                    functionRegex.findAll(html)
                ) {

                    val functionName =
                        functionMatch
                            .groupValues[1]

                    val body =
                        extractFunctionBody(
                            html,
                            functionMatch.range.first
                        ) ?: continue

                    /*
                     * dc_xxx(["...","..."])
                     */
                    val callRegex =
                        Regex(
                            Regex.escape(functionName) +
                                """\s*\(\s*\[([^\]]+)\]\s*\)"""
                        )

                    val callMatch =
                        callRegex.find(html)
                            ?: continue

                    val parts =
                        Regex(
                            """["']([^"']+)["']"""
                        )
                            .findAll(
                                callMatch.groupValues[1]
                            )
                            .map {
                                it.groupValues[1]
                            }
                            .toList()

                    if (parts.isEmpty()) {
                        continue
                    }

                    val steps =
                        mutableListOf<DecodeStep>()

                    /*
                     * Adımları gövdedeki sıralarına göre buluyoruz.
                     */
                    data class Candidate(
                        val position: Int,
                        val step: DecodeStep
                    )

                    val candidates =
                        mutableListOf<Candidate>()

                    /*
                     * Base64
                     */
                    val base64Regex =
                        Regex(
                            """=\s*atob\(\s*result\s*\)"""
                        )

                    for (
                        match in base64Regex.findAll(body)
                    ) {

                        candidates.add(
                            Candidate(
                                match.range.first,
                                DecodeStep("base64")
                            )
                        )
                    }

                    /*
                     * Reverse
                     */
                    val reverseRegex =
                        Regex(
                            """result\.split\(['"]['"]\)\.reverse\(\)\.join\(['"]['"]\)"""
                        )

                    for (
                        match in reverseRegex.findAll(body)
                    ) {

                        candidates.add(
                            Candidate(
                                match.range.first,
                                DecodeStep("reverse")
                            )
                        )
                    }

                    /*
                     * ROT-N
                     */
                    val rotRegex =
                        Regex(
                            """\(\s*o\s*-\s*base\s*\+\s*(\d+)\s*\)\s*%\s*26"""
                        )

                    for (
                        match in rotRegex.findAll(body)
                    ) {

                        val shift =
                            match
                                .groupValues
                                .getOrNull(1)
                                ?.toIntOrNull()
                                ?: continue

                        candidates.add(
                            Candidate(
                                match.range.first,
                                DecodeStep(
                                    type = "rot",
                                    value1 = shift
                                )
                            )
                        )
                    }

                    /*
                     * Character unmix.
                     *
                     * Hem yeni hem eski formu destekle.
                     */
                    val unmixRegex =
                        Regex(
                            """charCode\s*-\s*\(\s*(\d+)\s*%\s*\(\s*i\s*\+\s*(\d+)\s*\)"""
                        )

                    for (
                        match in unmixRegex.findAll(body)
                    ) {

                        val magic =
                            match
                                .groupValues
                                .getOrNull(1)
                                ?.toIntOrNull()
                                ?: continue

                        val offset =
                            match
                                .groupValues
                                .getOrNull(2)
                                ?.toIntOrNull()
                                ?: continue

                        candidates.add(
                            Candidate(
                                match.range.first,
                                DecodeStep(
                                    type = "unmix",
                                    value1 = magic,
                                    value2 = offset
                                )
                            )
                        )
                    }

                    /*
                     * Rolling XOR.
                     */
                    val xorRegex =
                        Regex(
                            """var\s+acc\s*=\s*(\d+)[\s\S]{0,300}?acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)\s*%\s*256"""
                        )

                    for (
                        match in xorRegex.findAll(body)
                    ) {

                        val seed =
                            match
                                .groupValues
                                .getOrNull(1)
                                ?.toIntOrNull()
                                ?: continue

                        val increment =
                            match
                                .groupValues
                                .getOrNull(2)
                                ?.toIntOrNull()
                                ?: continue

                        candidates.add(
                            Candidate(
                                match.range.first,
                                DecodeStep(
                                    type = "xor",
                                    value1 = seed,
                                    value2 = increment
                                )
                            )
                        )
                    }

                    candidates.sortBy {
                        it.position
                    }

                    steps.addAll(
                        candidates.map {
                            it.step
                        }
                    )

                    if (steps.isNotEmpty()) {

                        decoders.add(
                            InlineDecoder(
                                steps = steps,
                                parts = parts
                            )
                        )
                    }
                }

                return decoders
            }

            /*
             * ========================================================
             * INLINE DECODER UYGULA
             * ========================================================
             */

            fun applyDecodeSteps(
                parts: List<String>,
                steps: List<DecodeStep>
            ): String {

                var result =
                    parts.joinToString("")

                for (step in steps) {

                    result =
                        when (step.type) {

                            "base64" ->
                                base64Decode(
                                    result
                                )

                            "reverse" ->
                                result.reversed()

                            "rot" ->
                                rotN(
                                    result,
                                    step.value1
                                )

                            "unmix" ->
                                characterUnmix(
                                    result,
                                    step.value1.toLong(),
                                    step.value2
                                )

                            "xor" ->
                                rollingXor(
                                    result,
                                    step.value1,
                                    step.value2
                                )

                            else ->
                                result
                        }
                }

                return result
            }

            /*
             * ========================================================
             * LEGACY JS PACKER
             * ========================================================
             */


            /*
             * ========================================================
             * CUSTOM RPLAYER ARRAY DECODER
             * ========================================================
             *
             * Güncel hdfilmcehennemi.mobi player'ında unpack edilmiş
             * script içinde şu yapıya rastlandı:
             *
             *   function vwt(jkg) { ... }
             *   var p3k = vwt("...#...".split("#"));
             *
             * DİKKAT: Site ayraç karakterini değiştirebiliyor.
             * Eski sürümlerde "#", güncel sürümde "~" (tilde)
             * kullanılıyor. Bu nedenle ayraç parametre olarak
             * dışarıdan veriliyor.
             *
             * Bu ikinci katman klasik Dean-Edwards packer'dan ayrıdır.
             * Logcat'te görülen örnekte; Base64, reverse, alfabetik
             * kaydırma, deterministik shuffle ve rolling XOR uygulanıyor.
             */
            fun decodeRplayerArray(
                encoded: String,
                separator: String = "~"
            ): String? {
                return try {
                    /*
                     * Güncel rplayer array şemasında ayraç "~" (tilde).
                     * Eski sürümlerde "#" idi. Çağıran taraf yakaladığı
                     * ayracı verirse onu kullanıyoruz.
                     */
                    val parts =
                        encoded
                            .split(separator)
                            .toMutableList()

                    if (parts.size < 3) {
                        return null
                    }

                    val zyt00 =
                        parts.size - 2

                    val azaw =
                        zyt00 % 7

                    val z66 =
                        8 + (zyt00 % 5)

                    if (
                        azaw !in parts.indices ||
                        z66 !in parts.indices
                    ) {
                        return null
                    }

                    val dauei =
                        parts.removeAt(z66)

                    val bprd =
                        parts.removeAt(
                            if (azaw < parts.size) {
                                azaw
                            } else {
                                parts.lastIndex
                            }
                        )

                    var dtn =
                        parts.joinToString("")

                    if (bprd.length > 4096) {
                        dtn =
                            base64Decode(
                                dtn
                            )
                    }

                    var p72 = 0
                    var k04b = 0

                    for (
                        index in bprd.indices
                    ) {
                        val code =
                            bprd[index].code

                        p72 =
                            (
                                p72 * 37 +
                                    code
                                ) % 241

                        k04b =
                            (
                                k04b +
                                    (
                                        (code shl 1) xor
                                            index
                                    )
                                ) and 255
                    }

                    val td0 =
                        (
                            p72 * 3 +
                                k04b
                            ) % 256

                    val ixs =
                        (
                            k04b % 11
                        ) + 5

                    var byo9b =
                        (
                            k04b * 251L +
                                p72
                            ) % 65519L +
                                1L

                    /*
                     * JS:
                     * for(n=dauei.length-1;n>=0;n--){
                     *   ...
                     * }
                     */
                    for (
                        index in dauei.indices.reversed()
                    ) {
                        when (
                            val marker =
                                dauei[index]
                        ) {
                            '7' -> {
                                dtn =
                                    base64Decode(
                                        dtn
                                    )
                            }

                            '3' -> {
                                dtn =
                                    dtn.reversed()
                            }

                            else -> {
                                val shift =
                                    (
                                        26 -
                                            (
                                                (
                                                    marker.code -
                                                        96
                                                ) % 26
                                            )
                                        ) % 26

                                dtn =
                                    dtn.map { character ->
                                        when {
                                            character in 'a'..'z' -> {
                                                (
                                                    (
                                                        character.code -
                                                            'a'.code +
                                                            shift
                                                    ) % 26 +
                                                        'a'.code
                                                    )
                                                    .toChar()
                                            }

                                            character in 'A'..'Z' -> {
                                                (
                                                    (
                                                        character.code -
                                                            'A'.code +
                                                            shift
                                                    ) % 26 +
                                                        'A'.code
                                                    )
                                                    .toChar()
                                            }

                                            else -> {
                                                character
                                            }
                                        }
                                    }.joinToString("")
                            }
                        }
                    }

                    if (dauei.length > 2048) {
                        dtn =
                            dtn.reversed()
                    }

                    val chars =
                        dtn.toMutableList()

                    val shuffleKeys =
                        LongArray(
                            chars.size
                        )

                    for (
                        index in
                        chars.size - 1 downTo 1
                    ) {
                        byo9b =
                            (
                                byo9b * 97L +
                                    41L
                                ) % 65519L

                        shuffleKeys[index] =
                            byo9b %
                                (
                                    index + 1L
                                )
                    }

                    for (
                        index in
                        1 until chars.size
                    ) {
                        val swapIndex =
                            shuffleKeys[index]
                                .toInt()

                        if (
                            swapIndex in chars.indices
                        ) {
                            val temp =
                                chars[index]

                            chars[index] =
                                chars[swapIndex]

                            chars[swapIndex] =
                                temp
                        }
                    }

                    dtn =
                        chars.joinToString("")

                    var orm =
                        td0

                    val output =
                        StringBuilder(
                            dtn.length
                        )

                    for (
                        character in dtn
                    ) {
                        val code =
                            character.code

                        orm =
                            (
                                orm * 5 +
                                    ixs
                                ) and 255

                        output.append(
                            (
                                code xor orm
                            ).toChar()
                        )

                        orm =
                            (
                                orm +
                                    code
                            ) and 255
                    }

                    println(
                        "HDFilmCehennemi: CUSTOM ARRAY decode tamam -> " +
                            "parts=${parts.size} | sep='$separator' | " +
                            "output len=${output.length} | " +
                            "prefix=${output.toString().take(200)}"
                    )

                    output.toString()
                } catch (error: Exception) {
                    println(
                        "HDFilmCehennemi: CUSTOM ARRAY DECODER hatası -> " +
                            "${error::class.simpleName}: ${error.message}"
                    )
                    null
                }
            }

            fun decodeCustomPackedSources(
                decodedJs: String
            ): List<String> {
                val results =
                    linkedSetOf<String>()

                /*
                 * Ayraç karakteri site tarafından değiştirilebiliyor:
                 * eski sürümlerde "#", güncel sürümde "~".
                 * Bu yüzden ayracı da yakalıyoruz.
                 */
                val callRegex =
                    Regex(
                        """(?s)(?:var\s+)?\w+\s*=\s*\w+\(\s*["']([^"']{20,})["']\s*\.split\(\s*["']([^"']+)["']\s*\)\s*\)"""
                    )

                for (
                    match in callRegex.findAll(decodedJs)
                ) {
                    val encoded =
                        match.groupValues[1]

                    val separator =
                        match.groupValues
                            .getOrNull(2)
                            ?.takeIf { it.isNotBlank() }
                            ?: "~"

                    println(
                        "HDFilmCehennemi: CUSTOM ARRAY candidate -> " +
                            "sep='$separator' | parts=" +
                            encoded.split(separator).size +
                            " | len=${encoded.length}"
                    )

                    val decoded =
                        decodeRplayerArray(
                            encoded,
                            separator
                        )

                    if (
                        decoded.isNullOrBlank()
                    ) {
                        continue
                    }

                    val cleaned =
                        cleanVideoUrl(
                            decoded
                        )

                    if (
                        isValidVideoUrl(
                            cleaned
                        )
                    ) {
                        results.add(
                            cleaned!!
                        )
                    }

                    findVideoUrlInText(
                        decoded
                    )?.takeUnless {
                        it.contains(
                            "master.txt",
                            ignoreCase = true
                        )
                    }?.let {
                        results.add(
                            it
                        )
                    }

                    if (
                        decoded.contains(
                            "http",
                            ignoreCase = true
                        )
                    ) {
                        println(
                            "HDFilmCehennemi: CUSTOM ARRAY RESULT -> " +
                                decoded.take(500)
                        )
                    }
                }

                return results.toList()
            }

            fun unpackJs(
                packedCode: String,
                base: Int,
                keywords: String
            ): String {

                val dictionary =
                    keywords.split("|")

                fun decodeWord(
                    word: String
                ): String {

                    var number =
                        0

                    for (character in word) {

                        when {

                            character.isDigit() -> {
                                number =
                                    number * base +
                                        character.digitToInt()
                            }

                            character in 'a'..'z' -> {
                                number =
                                    number * base +
                                        character.code -
                                        'a'.code +
                                        10
                            }

                            character in 'A'..'Z' -> {
                                number =
                                    number * base +
                                        character.code -
                                        'A'.code +
                                        36
                            }
                        }
                    }

                    return if (
                        number >= 0 &&
                        number < dictionary.size &&
                        dictionary[number].isNotEmpty()
                    ) {
                        dictionary[number]
                    } else {
                        word
                    }
                }

                return Regex(
                    """\b\w+\b"""
                ).replace(
                    packedCode
                ) {
                    decodeWord(
                        it.value
                    )
                }
            }

            /*
             * ========================================================
             * ESKİ DECODE VARYANTLARI
             * ========================================================
             */

            fun decodeVariant1(
                value: String
            ): String {

                var result =
                    value.reversed()

                result =
                    rotN(
                        result,
                        13
                    )

                result =
                    base64Decode(
                        result
                    )

                return characterUnmix(
                    result
                )
            }

            fun decodeVariant2(
                value: String
            ): String {

                var result =
                    value.reversed()

                result =
                    base64Decode(
                        result
                    )

                result =
                    rotN(
                        result,
                        13
                    )

                return characterUnmix(
                    result
                )
            }

            fun decodeVariant3(
                value: String
            ): String {

                var result =
                    base64Decode(
                        value
                    )

                result =
                    result.reversed()

                result =
                    rotN(
                        result,
                        13
                    )

                return characterUnmix(
                    result
                )
            }

            fun decodeVideoUrl(
                parts: List<String>
            ): String? {

                val value =
                    parts.joinToString("")

                val variants =
                    listOf(
                        {
                            decodeVariant3(value)
                        },
                        {
                            decodeVariant1(value)
                        },
                        {
                            decodeVariant2(value)
                        }
                    )

                for (decoder in variants) {

                    try {

                        val result =
                            cleanVideoUrl(
                                decoder()
                            )

                        if (
                            isValidVideoUrl(result)
                        ) {
                            return result
                        }

                    } catch (
                        ignored: Exception
                    ) {
                    }
                }

                return null
            }

            /*
             * ========================================================
             * RAPIDRAME DOWNLOAD ÇÖZÜCÜ
             *
             * Güncel RPLAYER sayfasında gerçek filme ait kimlik:
             *   /rplayer/{id}/
             *
             * Aynı kimlik download sunucusunda:
             *   https://hdfilmcehennemi.download/download/{id}
             *
             * Önceki sürüm bu adresi sadece logluyordu ve daha sonra
             * stale bir playmix master.txt adresine düşüyordu. Burada
             * redirect zincirini, m3u8 cevabını ve HTML/JSON içindeki
             * gerçek medya URL'sini doğrudan çözüyoruz.
             * ========================================================
             */

            suspend fun resolveRapidDownload(
                rapidIframeUrl: String
            ): String? {

                val rapidId =
                    Regex(
                        """/rplayer/([^/?#]+)""",
                        setOf(RegexOption.IGNORE_CASE)
                    )
                        .find(rapidIframeUrl)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()

                if (rapidId.isNullOrBlank()) {
                    println(
                        "HDFilmCehennemi: Rapidrame ID bulunamadı -> $rapidIframeUrl"
                    )
                    return null
                }

                var currentUrl =
                    "https://hdfilmcehennemi.download/download/$rapidId"

                val visited = mutableSetOf<String>()

                for (attempt in 0 until 8) {

                    if (!visited.add(currentUrl)) {
                        continue
                    }

                    try {
                        val response =
                            app.get(
                                currentUrl,
                                allowRedirects = false,
                                headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Accept" to "*/*",
                                    "Referer" to rapidIframeUrl
                                )
                            )

                        val location =
                            response.headers["Location"]
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }

                        val contentType =
                            response.headers["Content-Type"]
                                ?.trim()
                                .orEmpty()

                        println(
                            "HDFilmCehennemi: RAPID DOWNLOAD -> " +
                                "status=${response.code} | " +
                                "type=$contentType | " +
                                "location=$location | url=$currentUrl"
                        )

                        if (location != null) {
                            val nextUrl =
                                try {
                                    URI(currentUrl)
                                        .resolve(location)
                                        .toString()
                                } catch (_: Exception) {
                                    location
                                }

                            val cleanedNext =
                                cleanVideoUrl(nextUrl)

                            if (
                                isValidVideoUrl(cleanedNext) &&
                                    !cleanedNext!!.contains(
                                        "master.txt",
                                        ignoreCase = true
                                    )
                            ) {
                                println(
                                    "HDFilmCehennemi: RAPID DOWNLOAD REDIRECT MEDYA -> $cleanedNext"
                                )
                                return cleanedNext
                            }

                            if (!cleanedNext.isNullOrBlank()) {
                                currentUrl = cleanedNext
                                continue
                            }
                        }

                        if (
                            response.code in 200..299 &&
                                (
                                    contentType.contains("video/", ignoreCase = true) ||
                                        contentType.contains("mpegurl", ignoreCase = true) ||
                                        contentType.contains("m3u8", ignoreCase = true)
                                    )
                        ) {
                            val bodyUrl =
                                cleanVideoUrl(currentUrl)

                            if (
                                bodyUrl != null &&
                                    !bodyUrl.contains("master.txt", ignoreCase = true)
                            ) {
                                println(
                                    "HDFilmCehennemi: RAPID DOWNLOAD DOĞRUDAN MEDYA -> $bodyUrl"
                                )
                                return bodyUrl
                            }
                        }

                        if (response.code in 200..299) {
                            val body =
                                response.text

                            findVideoUrlInText(body)?.let { found ->
                                if (!found.contains("master.txt", ignoreCase = true)) {
                                    println(
                                        "HDFilmCehennemi: RAPID DOWNLOAD BODY M3U8 -> $found"
                                    )
                                    return found
                                }
                            }

                            val genericUrl =
                                Regex(
                                    """https?://[^"'`<>\s]+(?:\.mp4(?:\?[^"'`<>\s]*)?|/hls/[^"'`<>\s]+)""",
                                    setOf(RegexOption.IGNORE_CASE)
                                )
                                    .find(body)
                                    ?.value
                                    ?.let { cleanVideoUrl(it) }

                            if (
                                isValidVideoUrl(genericUrl) &&
                                    !genericUrl!!.contains("master.txt", ignoreCase = true)
                            ) {
                                println(
                                    "HDFilmCehennemi: RAPID DOWNLOAD BODY MEDYA -> $genericUrl"
                                )
                                return genericUrl
                            }
                        }

                        if (response.code !in 300..399) {
                            break
                        }

                    } catch (error: Exception) {
                        println(
                            "HDFilmCehennemi: RAPID DOWNLOAD hata -> " +
                                "${error::class.simpleName}: ${error.message}"
                        )
                        break
                    }
                }

                println(
                    "HDFilmCehennemi: RAPID DOWNLOAD gerçek medya bulunamadı -> $rapidIframeUrl"
                )

                return null
            }

            /*
             * ========================================================
             * IFRAME SCRAPER
             * ========================================================
             */

            /* PACKED JS METINSEL AÇICI */
            fun unpackPackedJavaScript(packed: String): String? {
                return try {
                    var current = packed

                    /*
                     * Güncel player'da packed JS bir kez daha packed
                     * eval içerebiliyor. Bu nedenle aynı çözümü birkaç
                     * katman boyunca uygula. Normal tek katmanlı scriptlerde
                     * yalnızca ilk tur çalışır.
                     */
                    repeat(4) { layer ->
                        val marker = "eval(function(p,a,c,k,e,d)"
                        val start = current.indexOf(marker)

                        if (start < 0) {
                            return@repeat
                        }

                        val tail =
                            current.substring(start)

                        val re =
                            Regex(
                                """(?s)\('((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\.split\('\|'\)"""
                            )

                        val match =
                            re.find(tail)
                                ?: return@repeat

                        fun unescapePackedValue(
                            value: String
                        ): String =
                            value
                                .replace("\\\\'", "'")
                                .replace("\\\\\\\\", "\\")
                                .replace("\\\\n", "\n")
                                .replace("\\\\r", "\r")
                                .replace("\\\\t", "\t")

                        val payload =
                            unescapePackedValue(
                                match.groupValues[1]
                            )

                        val base =
                            match.groupValues[2]
                                .toInt()

                        val dictionary =
                            unescapePackedValue(
                                match.groupValues[4]
                            ).split("|")

                        fun parsePackedNumber(
                            value: String
                        ): Int {
                            if (value.isEmpty()) {
                                return 0
                            }

                            var number = 0

                            for (character in value) {
                                val digit =
                                    when {
                                        character in '0'..'9' ->
                                            character.code - 48

                                        character in 'a'..'z' ->
                                            character.code - 87

                                        character in 'A'..'Z' ->
                                            character.code - 29

                                        else ->
                                            return -1
                                    }

                                if (digit >= base) {
                                    return -1
                                }

                                number =
                                    number * base +
                                        digit
                            }

                            return number
                        }

                        val decoded =
                            Regex("""\b\w+\b""")
                                .replace(
                                    payload
                                ) { hit ->
                                    val index =
                                        parsePackedNumber(
                                            hit.value
                                        )

                                    if (
                                        index >= 0 &&
                                        index < dictionary.size &&
                                        dictionary[index].isNotEmpty()
                                    ) {
                                        dictionary[index]
                                    } else {
                                        hit.value
                                    }
                                }

                        println(
                            "HDFilmCehennemi: PACKER layer=$layer -> " +
                                "${current.length} -> ${decoded.length} bytes"
                        )

                        if (decoded == current) {
                            return@repeat
                        }

                        current = decoded
                    }

                    current
                } catch (error: Exception) {
                    println(
                        "HDFilmCehennemi: PACKER hata -> " +
                            "${error::class.simpleName}: ${error.message}"
                    )
                    null
                }
            }

            fun logLargeScript(tag: String, script: String, chunkSize: Int = 3000) {
                if (script.isBlank()) return

                val chunks = script.chunked(chunkSize)

                chunks.forEachIndexed { chunkIndex, chunk ->
                    println(
                        "HDFilmCehennemi: $tag[$chunkIndex/${chunks.size}] -> " +
                            chunk
                                .replace("\\n", " ")
                                .replace("\\r", " ")
                                .replace(Regex("\\\\s+"), " ")
                    )
                }
            }

            fun logTargetContext(
                tag: String,
                script: String,
                keyword: String,
                before: Int = 1800,
                after: Int = 5000
            ) {
                var from = 0
                var count = 0

                while (count < 20) {
                    val position =
                        script.indexOf(
                            keyword,
                            from,
                            ignoreCase = true
                        )

                    if (position < 0) break

                    val start =
                        maxOf(
                            0,
                            position - before
                        )

                    val end =
                        minOf(
                            script.length,
                            position + after
                        )

                    println(
                        "HDFilmCehennemi: $tag[$keyword][$count] -> " +
                            script
                                .substring(start, end)
                                .replace("\\n", " ")
                                .replace("\\r", " ")
                                .replace(Regex("\\\\s+"), " ")
                    )

                    from =
                        position +
                            keyword.length

                    count++
                }
            }

            fun logPackedDecode(script: String, index: Int) {
                val decoded = unpackPackedJavaScript(script)
                if (decoded.isNullOrBlank()) {
                    println("HDFilmCehennemi: PACKED[$index] açılamadı")
                    return
                }
                println("HDFilmCehennemi: PACKED[$index] DECODED bytes=${decoded.length}")
                val keywords = listOf("rniq6", "rt6", "m3u8", "master.txt", "playmix", "sources", "file:", "contentUrl", "fetch(", "XMLHttpRequest", "ajax")
                for (keyword in keywords) {
                    var from = 0
                    var count = 0
                    while (count < 10) {
                        val pos = decoded.indexOf(keyword, from, ignoreCase = true)
                        if (pos < 0) break
                        val a = maxOf(0, pos - 1500)
                        val b = minOf(decoded.length, pos + 3500)
                        println("HDFilmCehennemi: PACKED[$index][$keyword] -> " + decoded.substring(a, b).replace("\\n", " ").replace("\\r", " ").replace(Regex("\\\\s+"), " "))
                        from = pos + keyword.length
                        count++
                    }
                }
            }

            suspend fun scrapeIframe(
                iframeUrl: String
            ): String? {

                return try {

                    val iframeIsRapid =
                        iframeUrl.contains(
                            "/rplayer/",
                            ignoreCase = true
                        ) ||
                            iframeUrl.contains(
                                "rapidrame",
                                ignoreCase = true
                            )

                    /*
                     * Ana site referer'ı ile iframe'i al.
                     */
                    val iframeReferer =
                        "$mainUrl/"

                    val iframeHeaders =
                        mapOf(
                            "User-Agent" to userAgent,
                            "Accept" to
                                "text/html,application/xhtml+xml," +
                                "application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language" to
                                "tr-TR,tr;q=0.9,en;q=0.8",
                            "Referer" to iframeReferer
                        )

                    println(
                        "HDFilmCehennemi: iframe GET -> $iframeUrl"
                    )

                    val response =
                        app.get(
                            iframeUrl,
                            headers = iframeHeaders
                        )

                    val html =
                        response.text

                    val rawScriptBlocks = Regex(
                        """(?is)<script\b[^>]*>(.*?)</script\s*>"""
                    ).findAll(html).map { it.groupValues[1] }.toList()

                    println("HDFilmCehennemi: RPLAYER HAM SCRIPT SAYISI = ${rawScriptBlocks.size}")
                    rawScriptBlocks.forEachIndexed { scriptIndex, scriptBody ->
                        val compact =
                            scriptBody
                                .replace("\\n", " ")
                                .replace("\\r", " ")
                                .replace(Regex("\\\\s+"), " ")
                                .trim()

                        println(
                            "HDFilmCehennemi: HAM SCRIPT[$scriptIndex] " +
                                "bytes=${scriptBody.length} " +
                                "prefix=${compact.take(120)}"
                        )

                        /*
                         * qqxl1 / kp0y zincirini özellikle yakala.
                         *
                         * Güncel RPLAYER'da player config:
                         *     sources: [{file: qqxl1, type: "hls"}]
                         *
                         * qqxl1 ise başka bir inline script içinde
                         * dinamik olarak üretiliyor.
                         */
                        val sourceChain =
                            listOf(
                                "qqxl1",
                                "kp0y",
                                "d366",
                                "pmh25",
                                "g275m"
                            ).any {
                                compact.contains(
                                    it,
                                    ignoreCase = false
                                )
                            }

                        if (sourceChain) {
                            println(
                                "HDFilmCehennemi: RPLAYER SOURCE CHAIN " +
                                    "SCRIPT[$scriptIndex] bytes=${scriptBody.length}"
                            )

                            listOf(
                                "qqxl1",
                                "kp0y",
                                "d366",
                                "pmh25",
                                "g275m"
                            ).forEach { keyword ->
                                logTargetContext(
                                    tag =
                                        "RPLAYER SOURCE SCRIPT[$scriptIndex]",
                                    script =
                                        scriptBody,
                                    keyword =
                                        keyword
                                )
                            }

                            /*
                             * Script 9 gibi orta boy inline scriptlerde
                             * bağlamın tamamını görmek için ayrıca parçalı
                             * dump al.
                             */
                            if (
                                scriptBody.length <= 20000 &&
                                (
                                    compact.contains("kp0y") ||
                                    compact.contains("qqxl1")
                                )
                            ) {
                                logLargeScript(
                                    tag =
                                        "RPLAYER SOURCE FULL[$scriptIndex]",
                                    script =
                                        scriptBody
                                )
                            }
                        }

                        if (
                            compact.contains(
                                "eval(function(p,a,c,k,e,d)"
                            ) ||
                            compact.contains("rniq6")
                        ) {
                            logPackedDecode(
                                scriptBody,
                                scriptIndex
                            )
                        }
                    }

                    println(
                        "HDFilmCehennemi: iframe HTTP -> " +
                            response.code +
                            " | bytes=" +
                            html.length +
                            " | url=" +
                            iframeUrl
                    )

                    /*
                     * ------------------------------------------------
                     * RPLAYER TEŞHİSİ
                     *
                     * Güncel sitede /video/{id}/ endpoint'i gerçek
                     * iframe'i /rplayer/{token}/ olarak döndürüyor.
                     * Bu sayfanın video adresi doğrudan HTML'de olmayabilir;
                     * JS/player config içinden üretilebilir.
                     * ------------------------------------------------
                     */

                    if (iframeIsRapid) {

                        val previewLength =
                            minOf(
                                html.length,
                                5000
                            )

                        println(
                            "HDFilmCehennemi: RPLAYER HTML[0..$previewLength] -> " +
                                html.take(previewLength)
                                    .replace("\\n", " ")
                                    .replace("\\r", " ")
                                    .replace(Regex("\\s+"), " ")
                        )

                        val playerDocument =
                            org.jsoup.Jsoup.parse(
                                html,
                                iframeUrl
                            )

                        val sourceElements =
                            playerDocument.select(
                                "video source[src], video[src], source[src], " +
                                    "[data-src], [data-file], [data-url], " +
                                    "[data-video], [data-stream]"
                            )

                        println(
                            "HDFilmCehennemi: RPLAYER aday element sayısı = " +
                                sourceElements.size
                        )

                        sourceElements
                            .take(30)
                            .forEach { element ->

                                println(
                                    "HDFilmCehennemi: RPLAYER element -> " +
                                        element.tagName() +
                                        " | src=" +
                                        element.attr("src") +
                                        " | data-src=" +
                                        element.attr("data-src") +
                                        " | data-file=" +
                                        element.attr("data-file") +
                                        " | data-url=" +
                                        element.attr("data-url") +
                                        " | data-video=" +
                                        element.attr("data-video") +
                                        " | data-stream=" +
                                        element.attr("data-stream")
                                )
                            }

                        val interestingScripts =
                            playerDocument
                                .select("script")
                                .mapNotNull { script ->
                                    val scriptText =
                                        script.data()
                                            .ifBlank { script.html() }

                                    if (
                                        Regex(
                                            """(?i)(m3u8|master\.txt|\.mp4|playmix|jwplayer|videojs|sources|file\s*:|source\s*:|player)"""
                                        ).containsMatchIn(
                                            scriptText
                                        )
                                    ) {
                                        scriptText
                                    } else {
                                        null
                                    }
                                }

                        println(
                            "HDFilmCehennemi: RPLAYER ilginç script sayısı = " +
                                interestingScripts.size
                        )

                        interestingScripts
                            .take(20)
                            .forEachIndexed { index, scriptText ->

                                println(
                                    "HDFilmCehennemi: RPLAYER SCRIPT[$index] -> " +
                                        scriptText
                                            .take(5000)
                                            .replace("\\n", " ")
                                            .replace("\\r", " ")
                                            .replace(Regex("\\s+"), " ")
                                )
                            }

                        /*
                         * ------------------------------------------------
                         * HARİCİ SCRIPT İNCELEMESİ
                         *
                         * rt6 inline HTML'de tanımlı değilse değer harici
                         * JS dosyasından üretilebilir. Önce tüm script src
                         * adreslerini çıkar, sonra özellikle rt6 geçen
                         * dosyaları indirip çevresini logla.
                         * ------------------------------------------------
                         */
                        /* INLINE SCRIPT[3] HEDEF ANALIZI */
                        playerDocument.select("script").forEachIndexed { scriptIndex, script ->
                            val scriptText = script.data().ifBlank { script.html() }
                            if (scriptIndex == 3 || scriptText.contains("rt6", ignoreCase = true)) {
                                val keywords = listOf("rt6", "playmix", "master.txt", "contentUrl", "ergv8H1E1Or", "m3u8", "sources", "file:")
                                println("HDFilmCehennemi: INLINE SCRIPT TARGET[$scriptIndex] bytes=${scriptText.length}")
                                for (keyword in keywords) {
                                    var from = 0
                                    var count = 0
                                    while (count < 10) {
                                        val pos = scriptText.indexOf(keyword, from, ignoreCase = true)
                                        if (pos < 0) break
                                        val start = maxOf(0, pos - 2000)
                                        val end = minOf(scriptText.length, pos + 3000)
                                        println("HDFilmCehennemi: INLINE TARGET[$scriptIndex][$keyword] -> " + scriptText.substring(start, end).replace("\n", " ").replace("\r", " ").replace(Regex("\\s+"), " "))
                                        from = pos + keyword.length
                                        count++
                                    }
                                }
                            }
                        }

                        val externalScriptUrls =
                            playerDocument
                                .select("script[src]")
                                .mapNotNull { script ->
                                    val src = script.attr("src").trim()
                                    if (src.isBlank()) {
                                        null
                                    } else {
                                        try {
                                            URI(iframeUrl).resolve(src).toString()
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                }
                                .distinct()
                                .take(30)

                        println(
                            "HDFilmCehennemi: RPLAYER external script sayısı = " +
                                externalScriptUrls.size
                        )

                        externalScriptUrls.forEachIndexed { index, scriptUrl ->
                            println(
                                "HDFilmCehennemi: RPLAYER SCRIPT SRC[$index] -> " +
                                    scriptUrl
                            )
                        }

                        for ((index, scriptUrl) in externalScriptUrls.withIndex()) {
                            try {
                                val scriptResponse =
                                    app.get(
                                        scriptUrl,
                                        headers = mapOf(
                                            "User-Agent" to userAgent,
                                            "Accept" to "*/*",
                                            "Referer" to iframeUrl
                                        )
                                    )

                                val scriptBody = scriptResponse.text

                                println(
                                    "HDFilmCehennemi: RPLAYER SCRIPT HTTP[$index] -> " +
                                        scriptResponse.code +
                                        " | bytes=" +
                                        scriptBody.length +
                                        " | url=" +
                                        scriptUrl
                                )

                                if (scriptBody.contains("rt6", ignoreCase = false)) {
                                    val positions =
                                        Regex("rt6")
                                            .findAll(scriptBody)
                                            .map { it.range.first }
                                            .take(20)
                                            .toList()

                                    println(
                                        "HDFilmCehennemi: RPLAYER rt6 bulundu -> " +
                                            "script=$scriptUrl | adet=${positions.size}"
                                    )

                                    positions.forEach { position ->
                                        val start = maxOf(0, position - 800)
                                        val end = minOf(scriptBody.length, position + 1600)

                                        println(
                                            "HDFilmCehennemi: RPLAYER rt6 context -> " +
                                                scriptBody.substring(start, end)
                                                    .replace("\n", " ")
                                                    .replace("\r", " ")
                                                    .replace(Regex("\\s+"), " ")
                                        )
                                    }
                                }

                                val externalInteresting =
                                    Regex(
                                        """(?i)(rt6|m3u8|master\.txt|playmix|jwplayer|sources|file\s*:|fetch\s*\(|XMLHttpRequest|ajax|videoplayer|contentUrl|rapidrame)"""
                                    ).containsMatchIn(scriptBody)

                                if (externalInteresting) {
                                    val keywords = listOf(
                                        "rt6", "m3u8", "master.txt", "playmix", "jwplayer",
                                        "sources", "fetch(", "XMLHttpRequest", "ajax", "videoplayer",
                                        "contentUrl", "rapidrame"
                                    )

                                    println(
                                        "HDFilmCehennemi: RPLAYER HARICI SCRIPT ILGINC -> " +
                                            scriptUrl
                                    )

                                    for (keyword in keywords) {
                                        var from = 0
                                        var count = 0
                                        while (count < 20) {
                                            val pos = scriptBody.indexOf(keyword, from, ignoreCase = true)
                                            if (pos < 0) break
                                            val start = maxOf(0, pos - 700)
                                            val end = minOf(scriptBody.length, pos + 1400)
                                            println(
                                                "HDFilmCehennemi: RPLAYER KEYWORD[$keyword] -> " +
                                                    scriptBody.substring(start, end)
                                                        .replace("\n", " ")
                                                        .replace("\r", " ")
                                                        .replace(Regex("\\s+"), " ")
                                            )
                                            from = pos + keyword.length
                                            count++
                                        }
                                    }
                                }
                            } catch (error: Exception) {
                                println(
                                    "HDFilmCehennemi: RPLAYER SCRIPT GET hatası -> " +
                                        scriptUrl +
                                        " | " +
                                        "${error::class.simpleName}: ${error.message}"
                                )
                            }
                        }

                        val urlCandidates =
                            Regex(
                                """https?://[^\s"'<>\\]+"""
                            )
                                .findAll(html)
                                .map { it.value }
                                .distinct()
                                .take(100)
                                .toList()

                        println(
                            "HDFilmCehennemi: RPLAYER URL adayları = " +
                                urlCandidates.size
                        )

                        urlCandidates.forEach { candidate ->
                            println(
                                "HDFilmCehennemi: RPLAYER URL -> " +
                                    candidate
                            )
                        }
                    }

                    if (html.isBlank()) {
                        return null
                    }

                    /*
                     * ------------------------------------------------
                     * 1. RAPIDRAME DOWNLOAD ENDPOINT
                     * ------------------------------------------------
                     *
                     * Önce film kimliğine bağlı download endpoint'ini
                     * çöz. Böylece aşağıdaki genel HTML/decoder taraması
                     * stale master.txt seçse bile gerçek kaynak öncelikli
                     * olur.
                     */

                    if (iframeIsRapid) {
                        resolveRapidDownload(iframeUrl)?.let {
                            println(
                                "HDFilmCehennemi: Rapidrame gerçek video bulundu -> $it"
                            )
                            return it
                        }

                        /*
                         * Rapidrame HTML'i aynı film için bir hdfilmcehennemi.mobi
                         * embed adresi de taşıyor. master.txt stale/404 olduğunda
                         * bu film-spesifik sayfayı ikinci kaynak olarak çöz.
                         */
                        val movieEmbedCandidates =
                            Regex(
                                """https://hdfilmcehennemi\.mobi/video/embed/[^"'`<>\s]+""",
                                setOf(RegexOption.IGNORE_CASE)
                            )
                                .findAll(html)
                                .map {
                                    it.value
                                        .trimEnd(
                                            '"',
                                            '\'',
                                            '`',
                                            ',',
                                            ';',
                                            ')',
                                            ']',
                                            '}'
                                        )
                                }
                                .map { normalizeUrl(it) }
                                .distinct()
                                .take(5)
                                .toList()

                        if (movieEmbedCandidates.isNotEmpty()) {
                            println(
                                "HDFilmCehennemi: RPLAYER film embed adayları -> " +
                                    movieEmbedCandidates.joinToString(" | ")
                            )
                        }

                        for (movieEmbedUrl in movieEmbedCandidates) {
                            if (
                                movieEmbedUrl.equals(
                                    iframeUrl,
                                    ignoreCase = true
                                )
                            ) {
                                continue
                            }

                            try {
                                println(
                                    "HDFilmCehennemi: RPLAYER film embed deneniyor -> " +
                                        movieEmbedUrl
                                )

                                scrapeIframe(
                                    movieEmbedUrl
                                )?.takeUnless {
                                    it.contains(
                                        "master.txt",
                                        ignoreCase = true
                                    )
                                }?.let {
                                    println(
                                        "HDFilmCehennemi: RPLAYER film embed video bulundu -> $it"
                                    )
                                    return it
                                }
                            } catch (error: Exception) {
                                println(
                                    "HDFilmCehennemi: RPLAYER film embed hatası -> " +
                                        "${error::class.simpleName}: ${error.message}"
                                )
                            }
                        }
                    }

                    /*
                     * ------------------------------------------------
                     * 2. CUSTOM RPLAYER ARRAY DECODER
                     * ------------------------------------------------
                     *
                     * Güncel hdfilmcehennemi.mobi embed'inde JSON-LD
                     * içindeki master.txt stale olabiliyor. Bu yüzden
                     * genel URL taramasından ÖNCE packed JS'in ikinci
                     * vwt(array) katmanını çöz.
                     */
                    val unpackedForCustomDecoder =
                        unpackPackedJavaScript(
                            html
                        )

                    if (
                        !unpackedForCustomDecoder.isNullOrBlank()
                    ) {
                        println(
                            "HDFilmCehennemi: CUSTOM PACKED DECODED bytes=" +
                                unpackedForCustomDecoder.length
                        )

                        decodeCustomPackedSources(
                            unpackedForCustomDecoder
                        ).forEach { customUrl ->
                            println(
                                "HDFilmCehennemi: CUSTOM RPLAYER MEDYA -> " +
                                    customUrl
                            )
                            return customUrl
                        }
                    }

                    /*
                     * ------------------------------------------------
                     * 3. Doğrudan M3U8
                     * ------------------------------------------------
                     */

                    findVideoUrlInText(
                        html
                    )?.takeUnless {
                        /*
                         * Bu player sürümünde JSON-LD içindeki master.txt
                         * bilinen şekilde 404 dönüyor. Gerçek medya bulunursa
                         * custom decoder zaten yukarıda onu döndürüyor.
                         */
                        it.contains(
                            "master.txt",
                            ignoreCase = true
                        )
                    }?.let {
                        println(
                            "HDFilmCehennemi: doğrudan m3u8 bulundu"
                        )
                        return it
                    }

                    /*
                     * ------------------------------------------------
                     * 2. YENİ INLINE DC DECODER
                     * ------------------------------------------------
                     */

                    val inlineDecoders =
                        parseInlineDecoders(
                            html
                        )

                    println(
                        "HDFilmCehennemi: inline decoder sayısı = " +
                            inlineDecoders.size
                    )

                    for (
                        decoder in inlineDecoders
                    ) {

                        try {

                            val decoded =
                                applyDecodeSteps(
                                    decoder.parts,
                                    decoder.steps
                                )

                            val cleaned =
                                cleanVideoUrl(
                                    decoded
                                )

                            if (
                                isValidVideoUrl(
                                    cleaned
                                )
                            ) {

                                println(
                                    "HDFilmCehennemi: inline decoder m3u8 bulundu -> " +
                                        cleaned
                                )

                                return cleaned
                            }

                        } catch (error: Exception) {

                            println(
                                "HDFilmCehennemi: inline decoder hatası -> " +
                                    "${error::class.simpleName}: " +
                                    error.message
                            )
                        }
                    }

                    /*
                     * ------------------------------------------------
                     * 3. LEGACY PACKED JS
                     * ------------------------------------------------
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
                            packedMatch
                                .groupValues[1]

                        val base =
                            packedMatch
                                .groupValues[2]
                                .toInt()

                        val keywords =
                            packedMatch
                                .groupValues[4]

                        val decodedJs =
                            unpackJs(
                                packedCode,
                                base,
                                keywords
                            )

                        decodeCustomPackedSources(
                            decodedJs
                        ).forEach { customUrl ->
                            println(
                                "HDFilmCehennemi: CUSTOM RPLAYER MEDYA (LEGACY PACKED) -> " +
                                    customUrl
                            )
                            return customUrl
                        }

                        findVideoUrlInText(
                            decodedJs
                        )?.takeUnless {
                            it.contains(
                                "master.txt",
                                ignoreCase = true
                            )
                        }?.let {
                            return it
                        }

                        val partsMatch =
                            Regex(
                                """dc_\w+\(\[([^\]]+)\]\)"""
                            ).find(
                                decodedJs
                            )

                        if (partsMatch != null) {

                            val parts =
                                Regex(
                                    """["']([^"']+)["']"""
                                )
                                    .findAll(
                                        partsMatch.groupValues[1]
                                    )
                                    .map {
                                        it.groupValues[1]
                                    }
                                    .toList()

                            val decoded =
                                decodeVideoUrl(
                                    parts
                                )

                            if (
                                isValidVideoUrl(
                                    decoded
                                )
                            ) {
                                return decoded
                            }
                        }
                    }

                    /*
                     * ------------------------------------------------
                     * 4. dc_ çağrısını doğrudan HTML içinde ara
                     * ------------------------------------------------
                     */

                    val directPartsRegex =
                        Regex(
                            """dc_\w+\(\[([^\]]+)\]\)"""
                        )

                    val directPartsMatch =
                        directPartsRegex.find(
                            html
                        )

                    if (directPartsMatch != null) {

                        val parts =
                            Regex(
                                """["']([^"']+)["']"""
                            )
                                .findAll(
                                    directPartsMatch.groupValues[1]
                                )
                                .map {
                                    it.groupValues[1]
                                }
                                .toList()

                        val decoded =
                            decodeVideoUrl(
                                parts
                            )

                        if (
                            isValidVideoUrl(
                                decoded
                            )
                        ) {
                            return decoded
                        }
                    }

                    /*
                     * ------------------------------------------------
                     * 5. JSON-LD
                     * ------------------------------------------------
                     */

                    val jsonLdMatch =
                        Regex(
                            """<script[^>]+type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        )
                            .find(
                                html
                            )

                    if (jsonLdMatch != null) {

                        val jsonLd =
                            jsonLdMatch
                                .groupValues[1]

                        val contentUrl =
                            Regex(
                                """"contentUrl"\s*:\s*"([^"]+)"""",
                                setOf(
                                    RegexOption.IGNORE_CASE
                                )
                            )
                                .find(
                                    jsonLd
                                )
                                ?.groupValues
                                ?.getOrNull(1)

                        val cleaned =
                            cleanVideoUrl(
                                contentUrl
                            )

                        if (
                            isValidVideoUrl(
                                cleaned
                            )
                        ) {
                            return cleaned
                        }
                    }

                    println(
                        "HDFilmCehennemi: iframe içinde video URL bulunamadı -> " +
                            iframeUrl +
                            " | rapid=" +
                            iframeIsRapid
                    )

                    null

                } catch (error: Exception) {

                    println(
                        "HDFilmCehennemi iframe çözme hatası: " +
                            "${error::class.simpleName}: " +
                            error.message
                    )

                    null
                }
            }

            /*
             * ========================================================
             * IFRAME'LERİ DENE
             * ========================================================
             */

            var videoUrl: String? =
                null

            var usedIframe: String? =
                null

            for (
                iframeElement in iframeElements
            ) {

                val srcAttribute =
                    iframeElement.attr("src").trim()

                val dataSrcAttribute =
                    iframeElement.attr("data-src").trim()

                val rawIframe =
                    firstNonBlank(
                        srcAttribute.ifBlank { null },
                        dataSrcAttribute.ifBlank { null }
                    )

                if (rawIframe.isNullOrBlank()) {
                    println(
                        "HDFilmCehennemi: iframe atlandı -> src/data-src boş"
                    )
                    continue
                }

                val currentIframe =
                    normalizeUrl(
                        rawIframe
                    )

                println(
                    "HDFilmCehennemi: iframe adayı -> src=$srcAttribute | " +
                        "data-src=$dataSrcAttribute"
                )

                println(
                    "HDFilmCehennemi: iframe deneniyor -> " +
                        currentIframe
                )

                val currentVideo =
                    scrapeIframe(
                        currentIframe
                    )

                if (currentVideo != null) {

                    videoUrl =
                        currentVideo

                    usedIframe =
                        currentIframe

                    break
                }
            }

            /*
             * ========================================================
             * ALTERNATİF PLAYER
             * ========================================================
             */

            if (
                videoUrl == null ||
                    videoUrl?.contains("master.txt", ignoreCase = true) == true
            ) {

                for (
                    alternative in alternatives
                ) {

                        if (alternative.active) {
                            continue
                        }

                        if (
                            alternative.videoId
                                .isNullOrBlank()
                        ) {
                            continue
                        }

                        /*
                         * movie.js'in gerçek akışını burada birebir takip et:
                         *
                         *   GET /video/{data-video}/
                         *   -> JSON { data: { html: "<iframe ...>" } }
                         *   -> data.html içindeki iframe[data-src]
                         *
                         * Önceki sürüm data-video değerini doğrudan
                         * /video/embed/{filmId}/?rapidrame_id=... URL'sine
                         * çeviriyordu. Bu, güncel sitede yanlış/stale player
                         * katmanına gidebiliyordu.
                         */
                        val alternativeId =
                            alternative.videoId
                                ?.trim()
                                .orEmpty()

                        val videoEndpoint =
                            "$mainUrl/video/$alternativeId/"

                        val videoEndpointHeaders =
                            mapOf(
                                "User-Agent" to userAgent,
                                "Accept" to "application/json,text/plain,*/*",
                                "Content-Type" to "application/json",
                                "X-Requested-With" to "fetch",
                                "Referer" to pageUrl
                            )

                        println(
                            "HDFilmCehennemi: alternatif endpoint -> " +
                                videoEndpoint
                        )

                        try {

                            val endpointResponse =
                                app.get(
                                    videoEndpoint,
                                    headers = videoEndpointHeaders
                                )

                            val endpointBody =
                                endpointResponse.text

                            println(
                                "HDFilmCehennemi: alternatif endpoint HTTP -> " +
                                    endpointResponse.code +
                                    " | body=" +
                                    endpointBody.take(300)
                            )

                            val endpointJson =
                                org.json.JSONObject(endpointBody)

                            val returnedHtml =
                                endpointJson
                                    .optJSONObject("data")
                                    ?.optString("html")
                                    ?.trim()
                                    .orEmpty()

                            if (returnedHtml.isBlank()) {

                                println(
                                    "HDFilmCehennemi: /video/$alternativeId/ içinde data.html bulunamadı"
                                )

                                continue
                            }

                            val returnedDocument =
                                org.jsoup.Jsoup.parse(
                                    returnedHtml,
                                    mainUrl
                                )

                            val returnedIframe =
                                returnedDocument
                                    .select("iframe[data-src], iframe[src]")
                                    .firstOrNull()
                                    ?.let { iframe ->
                                        firstNonBlank(
                                            iframe.attr("data-src"),
                                            iframe.attr("src")
                                        )
                                    }
                                    ?.trim()

                            if (returnedIframe.isNullOrBlank()) {

                                println(
                                    "HDFilmCehennemi: data.html içinde iframe bulunamadı -> $videoEndpoint"
                                )

                                continue
                            }

                            val alternativeIframe =
                                normalizeUrl(returnedIframe)

                            println(
                                "HDFilmCehennemi: endpoint iframe -> " +
                                    alternativeIframe
                            )

                            val alternativeResult =
                                scrapeIframe(
                                    alternativeIframe
                                )

                            if (alternativeResult != null) {

                                videoUrl =
                                    alternativeResult

                                usedIframe =
                                    alternativeIframe

                                break
                            }

                        } catch (error: Exception) {

                            println(
                                "HDFilmCehennemi: alternatif endpoint hatası -> " +
                                    "${error::class.simpleName}: ${error.message}"
                            )
                        }
                    }
            }

            /*
             * ========================================================
             * VIDEO YOK
             * ========================================================
             */

            if (videoUrl.isNullOrBlank()) {

                println(
                    "HDFilmCehennemi: Video URL bulunamadı -> " +
                        pageUrl
                )

                return false
            }

            /*
             * ========================================================
             * MASTER.TXT -> GERÇEK HLS PLAYLIST
             *
             * Logcat'te Rapidrame bize örneğin:
             *
             * https://hls8.playmix.uno/hls/...mp4/master.txt
             *
             * döndürüyor. Bu adres CloudStream'in M3u8Helper'ına
             * doğrudan verildiğinde playlist olarak kabul edilmiyor.
             *
             * Bu nedenle master.txt önce GET ediliyor. İçeriği:
             * - gerçek bir #EXTM3U playlist ise aynı URL,
             * - başka bir .m3u8 URL'si ise o URL,
             * - JSON/HTML/JS içinde bir playlist URL'si ise bulunan URL
             * olarak çözülüyor.
             *
             * Böylece master.txt hiçbir zaman doğrudan M3u8Helper'a
             * gönderilmiyor.
             * ========================================================
             */

            suspend fun resolveMasterPlaylist(
                rawUrl: String,
                referer: String
            ): String? {

                val initialUrl =
                    cleanVideoUrl(rawUrl)
                        ?: return null

                if (
                    !initialUrl.contains(
                        "master.txt",
                        ignoreCase = true
                    )
                ) {
                    return initialUrl
                }

                val requestHeaders =
                    mapOf(
                        "User-Agent" to userAgent,
                        "Accept" to "*/*",
                        "Referer" to referer
                    )

                val queue =
                    ArrayDeque<String>()

                val visited =
                    mutableSetOf<String>()

                queue.add(initialUrl)

                repeat(8) {

                    if (queue.isEmpty()) {
                        return@repeat
                    }

                    val candidate =
                        queue.removeFirst()

                    if (!visited.add(candidate)) {
                        return@repeat
                    }

                    try {

                        println(
                            "HDFilmCehennemi: playlist çözülüyor -> $candidate"
                        )

                        val response =
                            app.get(
                                candidate,
                                headers = requestHeaders
                            )

                        val statusCode =
                            try {
                                response.code
                            } catch (ignored: Exception) {
                                -1
                            }

                        val contentType =
                            try {
                                response.headers["Content-Type"] ?: ""
                            } catch (ignored: Exception) {
                                ""
                            }

                        println(
                            "HDFilmCehennemi: playlist HTTP -> " +
                                "status=$statusCode | content-type=$contentType"
                        )

                        val body =
                            response.text
                                .trim()

                        if (body.isBlank()) {
                            println(
                                "HDFilmCehennemi: playlist yanıtı boş -> $candidate"
                            )
                            return@repeat
                        }

                        val preview =
                            body
                                .replace("\\r", " ")
                                .replace("\\n", " ")
                                .replace("\\t", " ")
                                .replace(Regex("\\s+"), " ")
                                .take(700)

                        println(
                            "HDFilmCehennemi: playlist body[0..700] -> $preview"
                        )

                        /*
                         * Gerçek HLS playlist'i.
                         * master.txt'in kendisi aslında HLS ise doğrudan
                         * aynı URL'yi CloudStream'e verebiliriz.
                         */
                        if (
                            body.startsWith("#EXTM3U") ||
                            body.contains("#EXT-X-STREAM-INF") ||
                            body.contains("#EXTINF:")
                        ) {

                            println(
                                "HDFilmCehennemi: gerçek HLS playlist bulundu -> $candidate"
                            )

                            return candidate
                        }

                        val normalizedBody =
                            body
                                .replace("\\/", "/")
                                .replace("&amp;", "&")
                                .replace("\\u002F", "/")
                                .replace("\\u0026", "&")
                                .replace("\\u003D", "=")
                                .trim()

                        /*
                         * JSON / HTML / JS içindeki mutlak playlist URL'leri.
                         * İlk sırada .m3u8, sonra master/index/playlist gibi
                         * HLS isimleri aranır.
                         */
                        val absolutePlaylistPatterns =
                            listOf(
                                Regex(
                                    """https?://[^\"'`<>\s]+\.m3u8(?:\?[^\"'`<>\s]*)?""",
                                    setOf(RegexOption.IGNORE_CASE)
                                ),
                                Regex(
                                    """https?://[^\"'`<>\s]+(?:master|index|playlist)[^\"'`<>\s]*(?:\.txt|\.m3u8)(?:\?[^\"'`<>\s]*)?""",
                                    setOf(RegexOption.IGNORE_CASE)
                                )
                            )

                        var absolutePlaylist: String? = null

                        for (pattern in absolutePlaylistPatterns) {
                            absolutePlaylist =
                                pattern
                                    .find(normalizedBody)
                                    ?.value
                                    ?.trimEnd(
                                        '\"', '\'', '`', ',', ';', ')', ']', '}'
                                    )

                            if (!absolutePlaylist.isNullOrBlank()) {
                                break
                            }
                        }

                        if (!absolutePlaylist.isNullOrBlank()) {

                            println(
                                "HDFilmCehennemi: yanıttan mutlak playlist bulundu -> " +
                                    absolutePlaylist
                            )

                            queue.add(absolutePlaylist)
                            return@repeat
                        }

                        /*
                         * Yanıt yalnızca bir URL ise onu da takip et.
                         */
                        val plainUrl =
                            normalizedBody
                                .lineSequence()
                                .map { it.trim() }
                                .firstOrNull { line ->
                                    line.startsWith("https://") ||
                                        line.startsWith("http://")
                                }

                        if (!plainUrl.isNullOrBlank()) {

                            val cleanedPlainUrl =
                                plainUrl.trim(
                                    '\"', '\'', '`', ',', ';', ')', ']', '}'
                                )

                            println(
                                "HDFilmCehennemi: yanıt doğrudan URL -> $cleanedPlainUrl"
                            )

                            queue.add(cleanedPlainUrl)
                            return@repeat
                        }

                        /*
                         * Göreli .m3u8 / playlist URL'si.
                         * URI.resolve(), ../ ve query/hash durumlarını da
                         * doğru şekilde ele alır.
                         */
                        val relativePlaylist =
                            Regex(
                                """(?:^|[\"' =:])(\.?\.?/[^\"'<>\s]+\.(?:m3u8|txt)(?:\?[^\"'<>\s]*)?)""",
                                setOf(RegexOption.IGNORE_CASE)
                            )
                                .find(normalizedBody)
                                ?.groupValues
                                ?.getOrNull(1)

                        if (!relativePlaylist.isNullOrBlank()) {

                            try {
                                val resolvedRelative =
                                    URI(candidate)
                                        .resolve(relativePlaylist)
                                        .toString()

                                println(
                                    "HDFilmCehennemi: yanıttan göreli playlist bulundu -> " +
                                        resolvedRelative
                                )

                                queue.add(resolvedRelative)
                                return@repeat
                            } catch (error: Exception) {
                                println(
                                    "HDFilmCehennemi: göreli playlist çözülemedi -> " +
                                        "${error::class.simpleName}: ${error.message}"
                                )
                            }
                        }

                        /*
                         * JSON/JS içinde uzantısız ama açıkça playlist'e işaret
                         * eden URL'leri yakala. Bu özellikle master.txt'in JSON
                         * döndürdüğü durumlar için.
                         */
                        val genericUrlPattern =
                            Regex(
                                """https?://[^\"'`<>\s]+""",
                                setOf(RegexOption.IGNORE_CASE)
                            )

                        val genericCandidates =
                            genericUrlPattern
                                .findAll(normalizedBody)
                                .map {
                                    it.value.trimEnd(
                                        '\"', '\'', '`', ',', ';', ')', ']', '}'
                                    )
                                }
                                .filter { value ->
                                    value.contains("/hls/", ignoreCase = true) ||
                                        value.contains("master", ignoreCase = true) ||
                                        value.contains("playlist", ignoreCase = true) ||
                                        value.contains("index", ignoreCase = true)
                                }
                                .distinct()
                                .take(4)
                                .toList()

                        if (genericCandidates.isNotEmpty()) {

                            println(
                                "HDFilmCehennemi: generic playlist adayları -> " +
                                    genericCandidates.joinToString(" | ")
                            )

                            genericCandidates.forEach {
                                queue.add(it)
                            }

                            return@repeat
                        }

                        println(
                            "HDFilmCehennemi: playlist yanıtında takip edilebilir HLS URL bulunamadı -> " +
                                "status=$statusCode | content-type=$contentType"
                        )

                    } catch (error: Exception) {

                        println(
                            "HDFilmCehennemi: playlist çözme hatası -> " +
                                "${error::class.simpleName}: ${error.message}"
                        )
                    }
                }

                println(
                    "HDFilmCehennemi: master.txt gerçek HLS'e çözülemedi -> $initialUrl"
                )

                return null
            }

            /*
             * ========================================================
             * NULLABLE -> STRING
             * ========================================================
             */

            val rawResolvedVideoUrl: String =
                cleanVideoUrl(
                    videoUrl
                ) ?: run {

                    println(
                        "HDFilmCehennemi: Video URL temizlenemedi -> " +
                            videoUrl
                    )

                    return false
                }

            val playlistReferer =
                "$mainUrl/"

            val resolvedVideoUrl =
                if (
                    rawResolvedVideoUrl.contains(
                        "master.txt",
                        ignoreCase = true
                    )
                ) {
                    resolveMasterPlaylist(
                        rawResolvedVideoUrl,
                        playlistReferer
                    )
                } else {
                    rawResolvedVideoUrl
                }

            if (resolvedVideoUrl.isNullOrBlank()) {

                println(
                    "HDFilmCehennemi: master.txt geçerli HLS'e çevrilemedi -> " +
                        rawResolvedVideoUrl
                )

                return false
            }

            if (
                !isValidVideoUrl(
                    resolvedVideoUrl
                )
            ) {

                println(
                    "HDFilmCehennemi: Geçersiz video URL -> " +
                        resolvedVideoUrl
                )

                return false
            }

            /*
             * ========================================================
             * KULLANILAN IFRAME'İN ORIGIN'İNİ BUL
             *
             * ÖNEMLİ:
             *
             * Önceden her Rapidrame için .ws kullanıyorduk.
             * Güncel sistemde ise iframe'in gerçek origin'i
             * kullanılmalı.
             * ========================================================
             */

            fun getOrigin(
                url: String?
            ): String {

                if (url.isNullOrBlank()) {
                    return mainUrl
                }

                return try {

                    val match =
                        Regex(
                            """^(https?://[^/]+)"""
                        ).find(url)

                    match
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: mainUrl

                } catch (
                    ignored: Exception
                ) {
                    mainUrl
                }
            }

            val embedOrigin =
                getOrigin(
                    usedIframe
                )

            val streamReferer =
                "$embedOrigin/"

            val streamOrigin =
                embedOrigin

            /*
             * ========================================================
             * RAPIDRAME BİLGİSİ
             * ========================================================
             */

            val isRapidrame =
                usedIframe?.contains(
                    "/rplayer/",
                    ignoreCase = true
                ) == true ||
                    usedIframe?.contains(
                        "rapidrame",
                        ignoreCase = true
                    ) == true ||
                    usedIframe?.contains(
                        "rapidrame_id=",
                        ignoreCase = true
                    ) == true ||
                    resolvedVideoUrl.contains(
                        "rapidrame.com",
                        ignoreCase = true
                    )

            println(
                "HDFilmCehennemi: video bulundu"
            )

            println(
                "HDFilmCehennemi: iframe = " +
                    usedIframe
            )

            println(
                "HDFilmCehennemi: m3u8 = " +
                    resolvedVideoUrl
            )

            println(
                "HDFilmCehennemi: rapidrame = " +
                    isRapidrame
            )

            println(
                "HDFilmCehennemi: embedOrigin = " +
                    embedOrigin
            )

            println(
                "HDFilmCehennemi: referer = " +
                    streamReferer
            )

            println(
                "HDFilmCehennemi: origin = " +
                    streamOrigin
            )

            /*
             * ========================================================
             * CLOUDSTREAM STREAM
             * ========================================================
             */

            val streamHeaders =
    mapOf(
        "User-Agent" to userAgent,
        "Referer" to streamReferer,
        "Origin" to streamOrigin,
        "Accept" to "*/*"
    )

try {

    M3u8Helper
        .generateM3u8(
            name =
                if (isRapidrame) {
                    "Rapidrame HLS"
                } else {
                    "HDFilmCehennemi HLS"
                },
            streamUrl = resolvedVideoUrl,
            referer = streamReferer,
            headers = streamHeaders,
            source = this.name
        )
        .forEach { extractorLink ->

            callback(
                extractorLink
            )
        }

} catch (error: Exception) {

    println(
        "HDFilmCehennemi: M3U8 generate hatası -> " +
            "${error::class.simpleName}: " +
            error.message
    )
}

            true

        } catch (error: Exception) {

            logError(
                "Rapidrame bağlantısı alınamadı: $data",
                error
            )

            false
        }
    }

    private fun findElement(
        document: Document,
        selector: String
    ): Element? {

        return document
            .select(selector)
            .firstOrNull()
    }

    private fun findElement(
        element: Element,
        selector: String
    ): Element? {

        return element
            .select(selector)
            .firstOrNull()
    }

    private fun normalizeUrl(
        rawUrl: String?
    ): String {

        if (rawUrl.isNullOrBlank()) {
            return ""
        }

        val value =
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
