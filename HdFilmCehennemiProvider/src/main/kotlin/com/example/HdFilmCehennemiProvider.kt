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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/128.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    /**
     * Ana sayfada gösterilecek kategoriler.
     *
     * URL'ler HDFilmCehennemi'nin mevcut kategori yapısına göre
     * düzenlenmiştir.
     */
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

    /**
     * WordPress benzeri sayfalama.
     *
     * 1. sayfada kategori URL'si,
     * sonraki sayfalarda /page/N/ kullanılır.
     */
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

    /**
     * Film/dizi kartlarını bulur.
     *
     * Sadece article'a bağlı kalmıyoruz.
     * Çünkü sitenin farklı listelerinde kart yapısı değişebiliyor.
     */
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
                // Bozuk tek bir kart bütün listeyi bozmasın.
            }
        }

        return results
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val contentUrl = normalizeUrl(
            attr("href")
        )

        if (!isValidContentUrl(contentUrl)) {
            return null
        }

        /*
         * Menü, kategori ve footer linklerini filtrelemek için
         * linkin kendisinden ve parent elementlerinden başlık arıyoruz.
         */
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

        /*
         * Çok kısa menü linklerini yanlışlıkla film olarak
         * almamak için minimum uzunluk kontrolü.
         */
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

    /**
     * Dizi bölümlerini bul.
     */
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

            val name = firstNonBlank(
                element.text(),
                element.attr("title"),
                element.attr("aria-label")
            )?.trim()

            if (name.isNullOrBlank()) {
                continue
            }

            episodes.add(
                newEpisode(episodeUrl) {
                    this.name = name
                }
            )
        }

        return episodes
            .distinctBy { it.data }
    }

    /**
     * Video kaynaklarını yükle.
     *
     * Burada HDFilmCehennemi sayfasındaki player/iframe
     * URL'lerini bulup CloudStream extractor sistemine veriyoruz.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl = normalizeUrl(data)

        val candidates = linkedSetOf<String>()

        try {

            val document = app.get(
                pageUrl,
                headers = defaultHeaders
            ).document

            /*
             * Iframe/player kaynakları.
             */
            val iframeElements = document.select(
                "iframe[src]," +
                    "iframe[data-src]," +
                    "iframe[data-url]," +
                    "iframe[data-embed]," +
                    "iframe[data-player]," +
                    "[data-iframe]," +
                    "[data-video]"
            )

            for (element in iframeElements) {

                addCandidate(
                    candidates,
                    element.attr("src")
                )

                addCandidate(
                    candidates,
                    element.attr("data-src")
                )

                addCandidate(
                    candidates,
                    element.attr("data-url")
                )

                addCandidate(
                    candidates,
                    element.attr("data-embed")
                )

                addCandidate(
                    candidates,
                    element.attr("data-player")
                )

                addCandidate(
                    candidates,
                    element.attr("data-iframe")
                )

                addCandidate(
                    candidates,
                    element.attr("data-video")
                )
            }

            /*
             * HTML5 video kaynakları.
             */
            val mediaElements = document.select(
                "video[src]," +
                    "video source[src]," +
                    "source[src]"
            )

            for (element in mediaElements) {

                addCandidate(
                    candidates,
                    element.attr("src")
                )
            }

            /*
             * Sayfanın JS'i içinde açıkça geçen player URL'leri.
             *
             * Burada sadece normal URL'leri yakalıyoruz.
             * Token/DRM/koruma aşma işlemi yapmıyoruz.
             */
            val html = document.html()

            val urlRegex = Regex(
                """https?://[^\s"'<>\\]+"""
            )

            for (match in urlRegex.findAll(html)) {

                val candidate = match.value
                    .replace("\\/", "/")
                    .trimEnd(
                        ')',
                        ',',
                        ';'
                    )

                val lower = candidate.lowercase()

                if (
                    lower.contains("rapidrame") ||
                    lower.contains("vidmoly") ||
                    lower.contains("vidrame") ||
                    lower.endsWith(".m3u8") ||
                    lower.endsWith(".mp4") ||
                    lower.endsWith(".mpd")
                ) {
                    addCandidate(
                        candidates,
                        candidate
                    )
                }
            }

        } catch (e: Exception) {

            logError(
                "Video sayfası okunamadı: $pageUrl",
                e
            )

            return false
        }

        if (candidates.isEmpty()) {
            logError(
                "Video kaynağı bulunamadı: $pageUrl",
                IllegalStateException("No candidate URLs")
            )

            return false
        }

        var found = false

        for (candidate in candidates) {

            try {

                val lower = candidate.lowercase()

                /*
                 * Doğrudan medya URL'si.
                 *
                 * CloudStream URL'den M3U8/DASH/VIDEO tipini
                 * otomatik algılayabilir.
                 */
                if (
                    lower.contains(".m3u8") ||
                    lower.contains(".mp4") ||
                    lower.contains(".mpd")
                ) {

                    val link = newExtractorLink(
                        source = "HDFilmCehennemi",
                        name = "HDFilmCehennemi",
                        url = candidate
                    ) {

                        referer = pageUrl

                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to pageUrl
                        )

                        quality = Qualities.Unknown.value
                    }

                    callback(link)

                    found = true

                    continue
                }

                /*
                 * Rapidrame / Vidmoly / diğer CloudStream
                 * extractor'larına bırak.
                 */
                val extractorFound = loadExtractor(
                    candidate,
                    pageUrl,
                    subtitleCallback,
                    callback
                )

                if (extractorFound) {
                    found = true
                }

            } catch (e: Exception) {

                logError(
                    "Extractor başarısız: $candidate",
                    e
                )
            }
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

        val cleaned = rawUrl
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace("\\/", "/")

        if (cleaned.isBlank()) {
            return
        }

        val url = normalizeUrl(cleaned)

        if (
            url.startsWith("http://") ||
            url.startsWith("https://")
        ) {
            candidates.add(url)
        }
    }

    /**
     * URL'yi güvenli şekilde absolute URL'ye çevir.
     */
    private fun normalizeUrl(
        rawUrl: String?
    ): String {

        if (rawUrl.isNullOrBlank()) {
            return ""
        }

        val value = rawUrl
            .trim()
            .replace("\\/", "/")

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

        /*
         * Site navigasyon linklerini sonuç listesinden çıkar.
         */
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

        if (ignored.any { lower.contains(it) }) {
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
