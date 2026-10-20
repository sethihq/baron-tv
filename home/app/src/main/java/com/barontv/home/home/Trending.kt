package com.barontv.home.home

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** One poster on the shelf: what it is, its picture, and the app OK opens. */
data class ShelfItem(
    val title: String,
    val meta: String,
    val image: String,
    val app: String,
    val packageName: String,
)

/** The shelf's three posters, wide first, and where they came from. */
data class ShelfData(val source: ShelfData.Source, val items: List<ShelfItem>) {
    enum class Source { TVMAZE, TMDB }

    fun toJson(): String = JSONObject()
        .put("source", source.name)
        .put("items", JSONArray(items.map {
            JSONObject().put("title", it.title).put("meta", it.meta).put("image", it.image)
                .put("app", it.app).put("package", it.packageName)
        }))
        .toString()

    companion object {
        fun fromJson(json: String): ShelfData = try {
            val root = JSONObject(json)
            val items = root.getJSONArray("items")
            ShelfData(
                Source.valueOf(root.getString("source")),
                List(items.length()) {
                    val o = items.getJSONObject(it)
                    ShelfItem(o.getString("title"), o.getString("meta"), o.getString("image"), o.getString("app"), o.getString("package"))
                },
            )
        } catch (e: JSONException) {
            throw IOException("Unreadable shelf cache", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unreadable shelf cache", e)
        }
    }
}

/** The streaming apps the shelf can point at, by the names TVmaze and TMDB give them. */
object Streaming {
    class Service(val name: String, val packageName: String, private val match: Regex) {
        fun matches(channel: String) = match.containsMatchIn(channel)
    }

    val SERVICES = listOf(
        Service("Prime Video", "com.amazon.amazonvideo.livingroom", Regex("prime video", RegexOption.IGNORE_CASE)),
        Service("JioHotstar", "in.startv.hotstar", Regex("hotstar", RegexOption.IGNORE_CASE)),
        Service("Netflix", "com.netflix.ninja", Regex("^netflix$", RegexOption.IGNORE_CASE)),
    )

    fun forChannel(channel: String?): Service? = channel?.let { c -> SERVICES.firstOrNull { it.matches(c) } }

    fun byName(name: String): Service? = SERVICES.firstOrNull { it.name == name }
}

/**
 * New on your apps, from TVmaze's streaming schedule (no key needed; its licence asks for credit, which
 * the shelf's head gives). The web schedule comes in two halves: country IN holds India's own services
 * (JioHotstar), and the global half holds the worldwide ones (Prime Video, Netflix). One day rarely has
 * three shows on this TV's apps, so the shelf looks back over the last week and shows the most-watched
 * three (TVmaze's weight), newest first among equals.
 */
object Tvmaze {
    /** A show with a new episode on one of the apps. */
    data class Candidate(
        val showId: Int,
        val name: String,
        val service: String,
        val type: String,
        val genres: List<String>,
        val language: String?,
        val weight: Int,
        val airstamp: String,
        val season: Int,
        val poster: String?,
        val posterLarge: String?,
    )

    /** The days the shelf looks back over, today included. */
    const val DAYS = 7

    fun scheduleUrl(date: String, india: Boolean) =
        "https://api.tvmaze.com/schedule/web?date=$date&country=${if (india) "IN" else ""}"

    fun imagesUrl(showId: Int) = "https://api.tvmaze.com/shows/$showId/images"

    // Live sport and news are regional (Thursday Night Football is a US stream): not for this shelf.
    private val SKIP_TYPES = setOf("Sports", "News")

    // What a household in Bangalore watches: English and India's languages.
    private val LANGUAGES = setOf(
        "English", "Hindi", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Marathi",
        "Punjabi", "Panjabi", "Gujarati", "Urdu", "Odia", "Oriya", "Assamese",
    )

    /** The episodes in one day's web schedule that are on an app the shelf knows, as candidates. */
    fun parseSchedule(json: String): List<Candidate> {
        val episodes = try {
            JSONArray(json)
        } catch (e: JSONException) {
            throw IOException("TVmaze sent something unexpected", e)
        }
        val out = ArrayList<Candidate>()
        for (i in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(i) ?: continue
            val show = episode.optJSONObject("_embedded")?.optJSONObject("show") ?: continue
            val channel = show.optJSONObject("webChannel")?.optString("name")
                ?: show.optJSONObject("network")?.optString("name")
            val service = Streaming.forChannel(channel) ?: continue
            val image = show.optJSONObject("image")
            out += Candidate(
                showId = show.optInt("id"),
                name = show.optString("name").trim(),
                service = service.name,
                type = show.optString("type"),
                genres = show.optJSONArray("genres")?.let { g -> List(g.length()) { g.optString(it) } } ?: emptyList(),
                language = show.optString("language").takeIf { it.isNotEmpty() && it != "null" },
                weight = show.optInt("weight"),
                airstamp = episode.optString("airstamp"),
                season = episode.optInt("season"),
                poster = image?.optString("medium")?.takeIf { it.startsWith("http") },
                posterLarge = image?.optString("original")?.takeIf { it.startsWith("http") },
            )
        }
        return out
    }

    /**
     * The shows worth a poster, best first: on an installed app, with a picture, not live sport or news,
     * in a language this home watches; one entry per show; the most-watched first, then the newest.
     */
    fun rank(candidates: List<Candidate>, installed: Set<String>): List<Candidate> = candidates
        .filter { c ->
            Streaming.byName(c.service)?.packageName in installed &&
                c.poster != null && c.name.isNotEmpty() && c.showId > 0 &&
                c.type !in SKIP_TYPES &&
                (c.language == null || c.language in LANGUAGES)
        }
        .sortedWith(compareByDescending<Candidate> { it.weight }.thenByDescending { it.airstamp })
        .distinctBy { it.showId }

    /**
     * A landscape picture TVmaze has for a show ("background"), or null: the show's main one if it has one,
     * otherwise the smallest (they come at 1920 or 3840 wide, and the wide slot is 416 px: a 4K picture
     * would only cost download time).
     */
    fun background(imagesJson: String): String? {
        val images = try {
            JSONArray(imagesJson)
        } catch (e: JSONException) {
            return null
        }
        var best: String? = null
        var bestMain = false
        var bestWidth = Int.MAX_VALUE
        for (i in 0 until images.length()) {
            val image = images.optJSONObject(i) ?: continue
            if (image.optString("type") != "background") continue
            val original = image.optJSONObject("resolutions")?.optJSONObject("original") ?: continue
            val url = original.optString("url")
            if (!url.startsWith("http")) continue
            val main = image.optBoolean("main")
            val width = original.optInt("width", Int.MAX_VALUE).takeIf { it > 0 } ?: Int.MAX_VALUE
            if (best == null || (main && !bestMain) || (main == bestMain && width < bestWidth)) {
                best = url
                bestMain = main
                bestWidth = width
            }
        }
        return best
    }

    /**
     * Three different shows: the wide slot takes the best one with a landscape picture (asking
     * [backgroundOf] for the first few, in order), the two tall ones the best two left. With no landscape
     * picture at all, the best show's poster fills the wide slot.
     */
    fun pick(ranked: List<Candidate>, backgroundOf: (Candidate) -> String?, tries: Int = 5): List<ShelfItem> {
        if (ranked.size < 3) return emptyList()
        var wide: Candidate? = null
        var wideImage: String? = null
        for (c in ranked.take(tries)) {
            val bg = backgroundOf(c) ?: continue
            wide = c
            wideImage = bg
            break
        }
        if (wide == null) {
            wide = ranked.first()
            wideImage = wide.posterLarge ?: wide.poster
        }
        val tall = ranked.filter { it !== wide }.take(2)
        return listOf(item(wide, wideImage!!, wide = true)) + tall.map { item(it, it.poster!!, wide = false) }
    }

    private fun item(c: Candidate, image: String, wide: Boolean): ShelfItem {
        val service = Streaming.byName(c.service)!!
        return ShelfItem(c.name, meta(c, wide), image, service.name, service.packageName)
    }

    /** The line under a poster, as the prototype's "Series · Drama · Prime Video". */
    fun meta(c: Candidate, wide: Boolean): String {
        val kind = when (c.type) {
            "Scripted", "" -> "Series"
            "Talk Show" -> "Talk show"
            "Game Show" -> "Game show"
            "Award Show" -> "Award show"
            "Panel Show" -> "Panel show"
            else -> c.type
        }
        val genre = c.genres.firstOrNull { it.isNotEmpty() && !it.equals(kind, ignoreCase = true) }
        val season = if (wide && c.season > 0) "Season ${c.season}" else null
        return listOfNotNull(kind, genre, season, c.service).joinToString(" · ")
    }
}

/**
 * Trending on your apps, from TMDB, when the build has a key (local.properties tmdb.key): the prototype's
 * fetchTrending, request for request. Discover for India on the Prime Video and Hotstar providers by
 * popularity; the wide slot takes the first with a backdrop, the tall ones the next two with posters.
 */
object Tmdb {
    private fun appFor(name: String): String = when {
        Regex("hotstar", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "JioHotstar"
        Regex("prime video", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Prime Video"
        else -> ""
    }

    fun get(http: Http, key: String, path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val query = StringBuilder()
        for ((k, v) in params) query.append(if (query.isEmpty()) '?' else '&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        // A v4 read token (a JWT) goes in a header; a v3 key goes in the query.
        val headers = mutableMapOf("Accept" to "application/json")
        if (key.startsWith("eyJ")) headers["Authorization"] = "Bearer $key"
        else query.append(if (query.isEmpty()) '?' else '&').append("api_key=").append(URLEncoder.encode(key, "UTF-8"))
        val body = http.getString("https://api.themoviedb.org/3$path$query", headers)
        return try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("TMDB sent something unexpected for $path", e)
        }
    }

    /** A title from discover, ready to be picked. */
    data class Title(
        val id: Int,
        val type: String,
        val title: String,
        val popularity: Double,
        val genreId: Int?,
        val backdrop: String?,
        val poster: String?,
    )

    fun titles(discover: JSONObject, type: String): List<Title> {
        val results = discover.optJSONArray("results") ?: return emptyList()
        return List(results.length()) { results.optJSONObject(it) }.filterNotNull().map { r ->
            Title(
                id = r.optInt("id"),
                type = type,
                title = r.optString(if (type == "movie") "title" else "name").ifEmpty { r.optString("title", r.optString("name")) },
                popularity = r.optDouble("popularity", 0.0),
                genreId = r.optJSONArray("genre_ids")?.takeIf { it.length() > 0 }?.optInt(0),
                backdrop = r.optString("backdrop_path").takeIf { it.startsWith("/") },
                poster = r.optString("poster_path").takeIf { it.startsWith("/") },
            )
        }
    }

    /** The prototype's three: the most popular with a backdrop, then the next two with posters. */
    fun pick(pool: List<Title>): List<Title> {
        val sorted = pool.sortedByDescending { it.popularity }
        val featured = sorted.firstOrNull { it.backdrop != null }
        val tall = sorted.filter { it !== featured && it.poster != null }.take(2)
        return listOfNotNull(featured) + tall
    }

    fun fetch(http: Http, key: String): List<ShelfItem> {
        val movieProviders = get(http, key, "/watch/providers/movie", mapOf("watch_region" to "IN"))
        val tvProviders = get(http, key, "/watch/providers/tv", mapOf("watch_region" to "IN"))
        val ids = LinkedHashSet<Int>()
        for (list in listOf(movieProviders, tvProviders)) {
            val results = list.optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val p = results.optJSONObject(i) ?: continue
                val name = p.optString("provider_name")
                if (appFor(name).isNotEmpty() && !name.contains("channel", ignoreCase = true)) ids += p.optInt("provider_id")
            }
        }
        val discover = mapOf(
            "watch_region" to "IN",
            "with_watch_providers" to ids.joinToString("|"),
            "sort_by" to "popularity.desc",
            "include_adult" to "false",
            "language" to "en-IN",
        )
        val movies = titles(get(http, key, "/discover/movie", discover), "movie")
        val shows = titles(get(http, key, "/discover/tv", discover), "tv")
        val genres = HashMap<Int, String>()
        for (type in listOf("movie", "tv")) {
            val list = get(http, key, "/genre/$type/list", mapOf("language" to "en")).optJSONArray("genres") ?: continue
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { genres[it.optInt("id")] = it.optString("name") }
        }
        return pick(movies + shows).mapIndexed { i, r ->
            val where = get(http, key, "/${r.type}/${r.id}/watch/providers").optJSONObject("results")?.optJSONObject("IN")
            var app = ""
            for (kind in listOf("flatrate", "ads", "free")) {
                val offers = where?.optJSONArray(kind) ?: continue
                for (k in 0 until offers.length()) {
                    if (app.isEmpty()) app = appFor(offers.optJSONObject(k)?.optString("provider_name") ?: "")
                }
            }
            var seasons = ""
            if (i == 0 && r.type == "tv") {
                val n = get(http, key, "/tv/${r.id}", mapOf("language" to "en-IN")).optInt("number_of_seasons")
                if (n > 0) seasons = if (n == 1) "1 season" else "$n seasons"
            }
            val name = app.ifEmpty { if (r.type == "tv") "JioHotstar" else "Prime Video" }
            ShelfItem(
                title = r.title,
                meta = listOf(if (r.type == "tv") "Series" else "Movie", genres[r.genreId] ?: "", seasons, app)
                    .filter { it.isNotEmpty() }.joinToString(" · "),
                image = if (i == 0) "https://image.tmdb.org/t/p/w780${r.backdrop}" else "https://image.tmdb.org/t/p/w342${r.poster}",
                app = name,
                packageName = Streaming.byName(name)!!.packageName,
            )
        }
    }
}
