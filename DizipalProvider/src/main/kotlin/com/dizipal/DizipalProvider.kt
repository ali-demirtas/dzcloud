package com.dizipal

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class DizipalProvider : MainAPI() {
    override var mainUrl = "https://dizipal2302.com"
    override var name = "Dizipal"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document
            .select("article a[href*='/film/'], article a[href*='/dizi/']")
            .distinctBy { fixUrl(it.attr("href")) }
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            if (items.isEmpty()) emptyList() else listOf(HomePageList("Öne Çıkanlar", items))
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val response = app.get(
            "$mainUrl/ajax-search?q=$encodedQuery",
            referer = mainUrl,
            headers = mapOf("Accept" to "application/json")
        )
        val results = runCatching { JSONObject(response.text).optJSONArray("results") }.getOrNull()
            ?: return emptyList()

        return (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document
        val ldCombined = document.select("script[type=application/ld+json]")
            .joinToString("\n") { it.data() }

        val title = document
            .selectFirst("h1.x5dc614, .xdd3add > h1, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("İçerik başlığı bulunamadı")
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".xe40a43 img, img")?.attr("src")
        )
        val descriptionFromLd = Regex("\"description\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])+)\"")
            .findAll(ldCombined)
            .map { decodeJsonString(it.groupValues[1]) }
            .firstOrNull { it.length > 40 }
        val description = descriptionFromLd
            ?: document.selectFirst(".xdb8c10 p")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description], meta[name=description]")
                ?.attr("content")
                ?.trim()

        val year = Regex("\"datePublished\"\\s*:\\s*\"?((?:19|20)\\d{2})\"?")
            .find(ldCombined)?.groupValues?.get(1)?.toIntOrNull()
        val imdbScore = Regex("\"ratingValue\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)\"?")
            .find(ldCombined)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("([0-9]+(?:\\.[0-9]+)?)\\s*IMDB", RegexOption.IGNORE_CASE)
                .find(document.text())?.groupValues?.get(1)?.toDoubleOrNull()
        val durationMinutes = Regex("\"duration\"\\s*:\\s*\"PT(\\d+)M\"", RegexOption.IGNORE_CASE)
            .find(ldCombined)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(\\d+)\\s*dk", RegexOption.IGNORE_CASE)
                .find(document.text())?.groupValues?.get(1)?.toIntOrNull()
        val trailerUrl = document.select("a[href]")
            .firstOrNull {
                val href = it.attr("href")
                it.text().contains("Fragman", ignoreCase = true) ||
                    href.contains("youtube.com", ignoreCase = true) ||
                    href.contains("youtu.be", ignoreCase = true)
            }
            ?.attr("href")
            ?.replace("https://www.youtube.com/embed/", "https://www.youtube.com/watch?v=")
            ?.replace("https://youtube.com/embed/", "https://www.youtube.com/watch?v=")

        return if (url.contains("/dizi/")) {
            val episodeRegex = Regex(
                "-(\\d+)-sezon-(\\d+)-bolum(?:[/?#].*)?$",
                RegexOption.IGNORE_CASE
            )
            val episodes = document.select("a[href*='/bolum/']")
                .distinctBy { fixUrl(it.attr("href")) }
                .mapNotNull { element ->
                    val episodeUrl = fixUrl(element.attr("href"))
                    val match = episodeRegex.find(episodeUrl) ?: return@mapNotNull null
                    val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val episode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null

                    newEpisode(episodeUrl) {
                        this.name = "$season. Sezon $episode. Bölüm"
                        this.season = season
                        this.episode = episode
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = imdbScore?.let { Score.from10(it) }
                this.duration = durationMinutes
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = imdbScore?.let { Score.from10(it) }
                this.duration = durationMinutes
                trailerUrl?.let {
                    this.trailers = mutableListOf(
                        TrailerData(extractorUrl = it, referer = url, raw = false)
                    )
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        val embeds = linkedSetOf<String>()

        document.selectFirst("#videoContainer[data-cfg]")
            ?.attr("data-cfg")
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded ->
                runCatching {
                    val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                    JSONObject(decoded).optString("v")
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let(embeds::add)
            }

        document.select("iframe").forEach { iframe ->
            iframe.attr("src").ifBlank { iframe.attr("data-src") }
                .takeIf { it.isNotBlank() }
                ?.let { embeds.add(fixUrl(it)) }
        }
        document.select(".sources-list a, .player-options a, [data-frame]").forEach { button ->
            button.attr("data-frame").ifBlank { button.attr("href") }
                .takeIf { it.isNotBlank() && !it.startsWith("#") }
                ?.let { embeds.add(fixUrl(it)) }
        }

        var emittedLink = false
        for (embedUrl in embeds) {
            if (embedUrl.contains("imagestoo.com/video/", ignoreCase = true)) {
                if (loadImagesToo(embedUrl, data, subtitleCallback, callback)) {
                    emittedLink = true
                }
                continue
            }

            if (embedUrl.contains(".m3u8", ignoreCase = true)) {
                callback(
                    newExtractorLink(name, "Dizipal HLS", embedUrl, ExtractorLinkType.M3U8) {
                        this.referer = data
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                emittedLink = true
                continue
            }

            // Let Cloudstream's host extractor run first. Besides the video link it
            // also parses host-specific subtitle tracks (for example ShowingCircle).
            var extractorProducedLink = false
            loadExtractor(
                embedUrl,
                data,
                subtitleCallback,
                callback = { link ->
                    extractorProducedLink = true
                    callback(link)
                }
            )
            if (extractorProducedLink) {
                emittedLink = true
                continue
            }

            val embedHtml = runCatching {
                app.get(embedUrl, referer = data).text
            }.getOrNull()
            val directHls = embedHtml?.let(::findDirectHls)
            if (directHls != null) {
                callback(
                    newExtractorLink(name, "Dizipal", directHls, ExtractorLinkType.M3U8) {
                        this.referer = embedUrl
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                emitSubtitle(embedHtml, embedUrl, subtitleCallback)
                emittedLink = true
            }
        }

        return emittedLink
    }

    private suspend fun loadImagesToo(
        embedUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = Regex("/video/([^/?#]+)", RegexOption.IGNORE_CASE)
            .find(embedUrl)?.groupValues?.get(1)
            ?: return false
        val origin = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)
            .find(embedUrl)?.groupValues?.get(1)
            ?: return false

        // This GET creates the fireplayer session cookie required by getVideo.
        val embedResponse = runCatching {
            app.get(embedUrl, referer = pageUrl)
        }.getOrNull() ?: return false
        emitSubtitle(embedResponse.text, embedUrl, subtitleCallback)

        val playerJson = runCatching {
            app.post(
                "$origin/player/index.php?data=$videoId&do=getVideo",
                data = mapOf("hash" to videoId, "r" to pageUrl),
                referer = embedUrl,
                cookies = embedResponse.cookies,
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Origin" to origin,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT
                )
            ).text
        }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false

        val hlsUrl = playerJson.optString("videoSource")
            .takeIf { it.isNotBlank() }
            ?: playerJson.optString("securedLink").takeIf { it.isNotBlank() }
            ?: return false

        val streamHeaders = mutableMapOf(
            "Origin" to origin,
            "User-Agent" to USER_AGENT
        )
        embedResponse.cookies.takeIf { it.isNotEmpty() }?.let { cookies ->
            streamHeaders["Cookie"] = cookies.entries.joinToString("; ") {
                "${it.key}=${it.value}"
            }
        }

        // Verify that FirePlayer returned HLS rather than an HTML/WAF error page.
        // The same session headers must also be passed to Media3 below.
        val masterText = runCatching {
            app.get(
                hlsUrl,
                referer = embedUrl,
                cookies = embedResponse.cookies,
                headers = streamHeaders,
                cacheTime = 0
            ).text
        }.getOrNull() ?: return false
        if (!masterText.trimStart().startsWith("#EXTM3U")) return false

        callback(
            newExtractorLink(name, "Dizipal", hlsUrl, ExtractorLinkType.M3U8) {
                this.referer = embedUrl
                this.headers = streamHeaders
            }
        )
        return true
    }

    private fun findDirectHls(html: String): String? {
        val unpacked = runCatching { getAndUnpack(html) }.getOrDefault(html)
        return Regex(
            "(?:M3U8|file|videoSource)\\s*[\"']?\\s*[:=]\\s*[\"']([^\"']+(?:\\.m3u8|/master\\.txt)[^\"']*)",
            RegexOption.IGNORE_CASE
        ).find(unpacked)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
    }

    private suspend fun emitSubtitle(
        html: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val subtitleConfig = Regex(
            "playerjsSubtitle\\s*=\\s*[\"']([^\"']+)",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) ?: return

        subtitleConfig.split(Regex(",(?=\\[)"))
            .mapNotNull { track ->
                Regex("^\\[([^]]+)](.+)$").find(track.trim())
            }
            .forEach { match ->
                val label = match.groupValues[1]
                val subtitleUrl = match.groupValues[2]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                if (subtitleUrl.startsWith("http")) {
                    subtitleCallback(
                        newSubtitleFile(label, subtitleUrl) {
                            headers = mapOf(
                                "Referer" to referer,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                }
            }
    }

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val title = optString("title").trim().takeIf { it.isNotEmpty() } ?: return null
        val url = optString("url").trim().takeIf { it.isNotEmpty() } ?: return null
        val poster = optString("poster").trim().takeIf { it.isNotEmpty() }
        val isSeries = optString("type").equals("Dizi", ignoreCase = true) || url.contains("/dizi/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (tagName() == "a") this else selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst("h2, h3, [class*=title], [class*=name]")
            ?.text()
            ?.trim()
            ?.removeSuffix(" izle")
            ?: link.text().trim()
        if (title.isEmpty()) return null

        val image = selectFirst("img")
        val poster = fixUrlNull(image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() })
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun decodeJsonString(value: String): String = runCatching {
        JSONObject("{\"value\":\"$value\"}").getString("value")
    }.getOrDefault(value.replace("\\/", "/"))
}
