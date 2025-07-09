package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 200L 
    override var sequentialMainPageScrollDelay = 200L  

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            val titleText = doc.select("title").text()
            if (titleText.contains("Just a moment...") ||
                titleText.contains("Bir dakika lütfen...") ||
                titleText.contains("Access denied")
            ) {
                Log.d("HDFilmCehennemiCF", "Cloudflare detected (Access Denied/Challenge), attempting bypass...")
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/"                                       to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/categories/nette-ilk-filmler/"               to "Nette İlk Filmler",
        "${mainUrl}/load/page/sayfano/home-series/"                                to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle2/"           to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/"                                      to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/"                              to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/"                                  to "En Çok Beğenilenler",
        "${mainUrl}/load/page/sayfano/genres/aile-filmleri-izleyin-6/"             to "Aile Filmleri",
        "${mainUrl}/load/page/sayfano/genres/aksiyon-filmleri-izleyin-5/"          to "Aksiyon Filmleri",
        "${mainUrl}/load/page/sayfano/genres/animasyon-filmlerini-izle-5/"      to "Animasyon Filmleri",
        "${mainUrl}/load/page/sayfano/genres/belgesel-filmlerini-izle-1/"          to "Belgesel Filmleri",
        "${mainUrl}/load/page/sayfano/genres/bilim-kurgu-filmlerini-izleyin-3/"    to "Bilim Kurgu Filmleri",
        "${mainUrl}/load/page/sayfano/genres/komedi-filmlerini-izleyin-1/"         to "Komedi Filmleri",
        "${mainUrl}/load/page/sayfano/genres/korku-filmlerini-izle-4/"             to "Korku Filmleri",
        "${mainUrl}/load/page/sayfano/genres/romantik-filmleri-izle-2/"            to "Romantik Filmleri",
        "${mainUrl}/load/page/sayfano/genres/suc-filmleri-izle-3/"                 to "Suç Filmleri",
        "${mainUrl}/load/page/sayfano/genres/tarih-filmleri-izle-4/"               to "Tarih Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val url = request.data.replace("sayfano", page.toString())
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*", "X-Requested-With" to "fetch"
        )
        val doc = app.get(url, headers = headers, referer = mainUrl, interceptor = interceptor)
        
        val home: List<SearchResponse>?
        if (!doc.toString().contains("Sayfa Bulunamadı")) {
            val hdfcData = objectMapper.readValue<HDFC>(doc.toString())
            val document = Jsoup.parse(hdfcData.html)

            home = document.select("a").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home)
        }
        return newHomePageResponse(request.name, emptyList())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()
        
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val schemaScript = document.selectFirst("script[type=application/ld+json]")
        val schemaJson = schemaScript?.data()

        val parsedSchema: MovieSchema? = if (!schemaJson.isNullOrEmpty()) {
            try {
                objectMapper.readValue<MovieSchema>(schemaJson)
            } catch (e: Exception) {
                Log.e("HDFilmCehennemi", "Error parsing schema: ${e.message}", e)
                null
            }
        } else null

        val title = parsedSchema?.name ?: document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle")?.trim() ?: ""
        val poster = fixUrlNull(parsedSchema?.image) ?: fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val description = parsedSchema?.description ?: document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val tags = parsedSchema?.genre ?: document.select("div.post-info-genres a").map { it.text() }
        val year = parsedSchema?.datePublished?.substringBefore("-")?.toIntOrNull() ?: document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val duration = parsedSchema?.StringDuration?.let { Regex("""PT(\d+)M""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val rating = parsedSchema?.aggregateRating?.ratingValue?.toRatingInt() ?: document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()?.toRatingInt()
        val actors = parsedSchema?.actor?.mapNotNull { it.name?.trim() }?.map { Actor(it) } ?: document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }
        val trailer = fixUrlNull(parsedSchema?.trailer?.embedUrl) ?: document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }

        val tvType = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.duration = duration
                this.rating = rating
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit ) {
        val script = app.get(url, referer = "${mainUrl}/", interceptor = interceptor).document.select("script").find { it.data().contains("sources:") }?.data() ?: return
        Log.d("HDCH", "script » $script")
        val videoData = getAndUnpack(script).substringAfter("file_link=\"").substringBefore("\";")
        Log.d("HDCH", "videoData » $videoData")
        val subData = script.substringAfter("tracks: [").substringBefore("]")
        Log.d("HDCH", "subData » $subData")
        AppUtils.tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions"}?.map {
            val subtitleUrl = "${mainUrl}${it.file}/"

            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                "Referer" to "subtitleUrl"
            )
            val subtitleResponse = app.get(subtitleUrl, headers = headers, allowRedirects=true)
            if (subtitleResponse.isSuccessful) {
                subtitleCallback(SubtitleFile(it.language.toString(), subtitleUrl))
                Log.d("HDCH", "Subtitle added: $subtitleUrl")
            } else {
                Log.d("HDCH", "Subtitle URL inaccessible: ${subtitleResponse.code}")
            }
        }
        callback.invoke(
            newExtractorLink(
                source = source,
                name = source,
                url = base64Decode(videoData),
                type = INFER_TYPE
            ) {
                headers = mapOf("Referer" to "${mainUrl}/", "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Norton/124.0.0.0")
                quality = Qualities.Unknown.value
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDFilmCehennemi", "data » $data")
        val document = app.get(data, interceptor = interceptor).document

        val mainPlayerIframe = document.selectFirst("div.video-player-container-here iframe.close[data-src]")
        val mainIframeSrc = fixUrlNull(mainPlayerIframe?.attr("data-src"))

        if (mainIframeSrc != null) {
            Log.d("HDFilmCehennemi", "Main player iframe found: $mainIframeSrc")
            loadExtractor(mainIframeSrc, mainUrl, subtitleCallback, callback)
        } else {
            Log.w("HDFilmCehennemi", "Main player iframe (div.video-player-container-here iframe.close[data-src]) not found on page: $data")
        }

        document.select("nav.video-alternatives div.alternative-links button.alternative-link").forEach { button ->
            val videoID = button.attr("data-video")
            val sourceName = button.text().trim()
            val langCode = button.parents().firstOrNull()?.attr("data-lang")

            if (videoID.isNotEmpty()) {
                Log.d("HDFilmCehennemi", "Processing alternative link: $sourceName (Video ID: $videoID, Lang: $langCode)")
                val apiGetText = app.get(
                    "${mainUrl}/video/$videoID/", interceptor = interceptor,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-Requested-With" to "fetch"
                    ),
                    referer = data
                ).text
                
                var iframeSrcFromApi = Regex("""data-src=\\"([^"]+)""").find(apiGetText)?.groupValues?.get(1)?.replace("\\", "")

                if (iframeSrcFromApi != null) {
                    iframeSrcFromApi = iframeSrcFromApi.replace("{rapidrame_id}", "")
                    
                    if (iframeSrcFromApi.contains("rapidrame")) {
                        iframeSrcFromApi = "${mainUrl}/rplayer/${iframeSrcFromApi.substringAfter("?rapidrame_id=")}"
                        Log.d("HDFilmCehennemi", "Transformed to Rapidrame URL: $iframeSrcFromApi")
                    } else if (iframeSrcFromApi.contains("mobi")) {
                        val iframeDocFromApi = Jsoup.parse(apiGetText)
                        iframeSrcFromApi = fixUrlNull(iframeDocFromApi.selectFirst("iframe")?.attr("data-src")?.replace("\"","")?.replace("\\", "")?.replace("{rapidrame_id}", ""))
                        Log.d("HDFilmCehennemi", "Parsed mobi iframe URL: $iframeSrcFromApi")
                    }

                    if (!iframeSrcFromApi.isNullOrEmpty()) {
                        Log.d("HDFilmCehennemi", "Final iframe URL to loadExtractor: $iframeSrcFromApi (Source: $sourceName)")
                        loadExtractor(iframeSrcFromApi, mainUrl, subtitleCallback, callback)
                    } else {
                        Log.e("HDFilmCehennemi", "Final iframe src is null or empty after processing API response for $videoID.")
                    }
                } else {
                    Log.e("HDFilmCehennemi", "Could not extract iframe src from API response for videoID: $videoID. Response: $apiGetText")
                }
            }
        }
        return true
    }
    
    private data class SubSource(
        @JsonProperty("file")    val file: String?  = null,
        @JsonProperty("label")   val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("kind")    val kind: String?  = null
    )

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
    data class HDFC(
        @JsonProperty("html") val html: String,
        @JsonProperty("meta") val meta: Meta
    )

    data class Meta(
        @JsonProperty("title") val title: String,
        @JsonProperty("canonical") val canonical: Boolean,
        @JsonProperty("keywords") val keywords: Boolean
    )

    private data class MovieSchema(
        @JsonProperty("@type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("datePublished") val datePublished: String? = null,
        @JsonProperty("duration") val StringDuration: String? = null,
        @JsonProperty("actor") val actor: List<ActorSchema>? = null,
        @JsonProperty("aggregateRating") val aggregateRating: AggregateRatingSchema? = null,
        @JsonProperty("director") val director: DirectorSchema? = null
    )

    private data class TrailerSchema(
        @JsonProperty("@type") val type: String? = null,
        @JsonProperty("embedUrl") val embedUrl: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumbnailUrl") val thumbnailUrl: String? = null
    )

    private data class ActorSchema(
        @JsonProperty("@type") val type: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    private data class DirectorSchema(
        @JsonProperty("@type") val type: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    private data class AggregateRatingSchema(
        @JsonProperty("@type") val type: String? = null,
        @JsonProperty("ratingValue") val ratingValue: String? = null,
        @JsonProperty("bestRating") val bestRating: String? = null,
        @JsonProperty("worstRating") val worstRating: String? = null,
        @JsonProperty("ratingCount") val ratingCount: String? = null
    )
}
