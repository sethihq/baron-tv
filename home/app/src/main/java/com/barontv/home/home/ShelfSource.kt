package com.barontv.home.home

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Where the shelf's posters come from and where they're kept. TMDB when the build has a key, TVmaze
 * otherwise. Everything lands in the cache directory, so the shelf shows at once after a reboot and works
 * offline: the last three posters (home-shelf.json), their pictures already cut to size (home-img/), and
 * TVmaze's past days, which don't change (home-tvmaze/), so a refresh only asks for today.
 *
 * Blocking I/O throughout: run it on a background dispatcher.
 */
class ShelfSource(cacheDir: File, private val http: Http, private val tmdbKey: String) {
    private val file = File(cacheDir, "home-shelf.json")
    private val days = File(cacheDir, "home-tvmaze")
    private val pictures = File(cacheDir, "home-img")

    val source: ShelfData.Source get() = if (tmdbKey.isNotEmpty()) ShelfData.Source.TMDB else ShelfData.Source.TVMAZE

    /** The last shelf fetched from the current source, if there is one. */
    fun cached(): ShelfData? = try {
        if (file.isFile) ShelfData.fromJson(file.readText()).takeIf { it.source == source && it.items.size == 3 } else null
    } catch (e: IOException) {
        null
    }

    /** How long ago the cached shelf came; a clock that went backwards counts as old. */
    fun age(now: Long): Long = if (file.isFile) (now - file.lastModified()).let { if (it < 0) Long.MAX_VALUE else it } else Long.MAX_VALUE

    /** The service answered, but with fewer than three shows on this TV's apps: nothing to fix by asking again soon. */
    class NotEnoughShows(count: Int) : IOException("Only $count shows to show")

    /**
     * A fresh shelf for [now], from the apps in [installed], saved for next time. Throws IOException when
     * offline, and [NotEnoughShows] when there aren't three to show (the old shelf, if any, stays).
     */
    suspend fun fetch(now: Long, installed: Set<String>): ShelfData {
        val items = if (source == ShelfData.Source.TMDB) Tmdb.fetch(http, tmdbKey) else fromTvmaze(now, installed)
        if (items.size != 3) throw NotEnoughShows(items.size)
        val data = ShelfData(source, items)
        writeAtomically(file, data.toJson())
        return data
    }

    private suspend fun fromTvmaze(now: Long, installed: Set<String>): List<ShelfItem> {
        days.mkdirs()
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = Weather.ZONE }
        val calendar = Calendar.getInstance(Weather.ZONE).apply { timeInMillis = now }
        val dates = List(Tvmaze.DAYS) {
            format.format(calendar.time).also { calendar.add(Calendar.DAY_OF_MONTH, -1) }
        }
        val all = ArrayList<Tvmaze.Candidate>()
        for ((back, date) in dates.withIndex()) {
            for (india in listOf(true, false)) all += day(date, india, fresh = back == 0)
        }
        pruneDays(dates.toSet())
        val ranked = Tvmaze.rank(all, installed)
        // The wide slot wants a landscape picture: ask for the best few shows' pictures, in order, until
        // one has one.
        val backgrounds = HashMap<Int, String?>()
        for (c in ranked.take(5)) {
            val bg = try {
                Tvmaze.background(tvmaze(Tvmaze.imagesUrl(c.showId)))
            } catch (e: IOException) {
                null
            }
            backgrounds[c.showId] = bg
            if (bg != null) break
        }
        return Tvmaze.pick(ranked, { backgrounds[it.showId] })
    }

    /** One day's candidates: from disk for past days already seen, from TVmaze otherwise. */
    private suspend fun day(date: String, india: Boolean, fresh: Boolean): List<Tvmaze.Candidate> {
        val saved = File(days, "$date-${if (india) "in" else "global"}.json")
        if (!fresh && saved.isFile) {
            try {
                return readCandidates(saved.readText())
            } catch (e: IOException) {
                saved.delete()
            }
        }
        val list = Tvmaze.parseSchedule(tvmaze(Tvmaze.scheduleUrl(date, india)))
        writeAtomically(saved, writeCandidates(list))
        return list
    }

    /** TVmaze allows 20 calls in 10 s: a short pause between calls, and one wait and retry if it says so. */
    private suspend fun tvmaze(url: String): String {
        yield()
        delay(PACE_MS)
        return try {
            http.getString(url)
        } catch (e: HttpStatusException) {
            if (e.code != 429) throw e
            delay(2_000)
            http.getString(url)
        }
    }

    private fun pruneDays(keep: Set<String>) {
        days.listFiles()?.forEach { f -> if (f.name.take(10) !in keep) f.delete() }
    }

    /**
     * The picture for poster [item] at [width] x [height] px: from memory, from disk, or (when [online])
     * downloaded, cut to size and kept. Null if there's none to be had.
     */
    fun picture(item: ShelfItem, width: Int, height: Int, online: Boolean): Bitmap? {
        val key = Images.keyFor(item.image, width, height)
        Images.remembered(key)?.let { return it }
        pictures.mkdirs()
        val kept = File(pictures, "$key.jpg")
        if (kept.isFile) {
            Images.decodeFile(kept, width, height, opaque = true)?.let {
                Images.remember(key, it)
                return it
            }
            kept.delete()
        }
        if (!online) return null
        val download = File(pictures, "$key.part")
        return try {
            http.download(item.image, download)
            val fitted = Images.decodeFile(download, width, height, opaque = true) ?: return null
            Images.save(fitted, kept)
            Images.remember(key, fitted)
            fitted
        } catch (e: IOException) {
            null
        } finally {
            download.delete()
        }
    }

    /** Removes kept pictures that aren't one of [keys] (the shelf on screen), so the folder stays small. */
    fun prunePictures(keys: Set<String>) {
        pictures.listFiles()?.forEach { f -> if (f.name.substringBefore('.') !in keys) f.delete() }
    }

    private fun writeCandidates(list: List<Tvmaze.Candidate>): String = JSONArray(list.map { c ->
        JSONObject().put("id", c.showId).put("name", c.name).put("service", c.service).put("type", c.type)
            .put("genres", JSONArray(c.genres)).put("language", c.language ?: JSONObject.NULL).put("weight", c.weight)
            .put("airstamp", c.airstamp).put("season", c.season).put("poster", c.poster ?: JSONObject.NULL)
            .put("posterLarge", c.posterLarge ?: JSONObject.NULL)
    }).toString()

    private fun readCandidates(json: String): List<Tvmaze.Candidate> = try {
        val array = JSONArray(json)
        List(array.length()) {
            val o = array.getJSONObject(it)
            val genres = o.getJSONArray("genres")
            Tvmaze.Candidate(
                showId = o.getInt("id"),
                name = o.getString("name"),
                service = o.getString("service"),
                type = o.getString("type"),
                genres = List(genres.length()) { g -> genres.getString(g) },
                language = o.optString("language").takeUnless { o.isNull("language") },
                weight = o.getInt("weight"),
                airstamp = o.getString("airstamp"),
                season = o.getInt("season"),
                poster = o.optString("poster").takeUnless { o.isNull("poster") },
                posterLarge = o.optString("posterLarge").takeUnless { o.isNull("posterLarge") },
            )
        }
    } catch (e: JSONException) {
        throw IOException("Unreadable day file", e)
    }

    companion object {
        /** A few times a day: the shelf is "new this week", so every 4 hours is plenty. */
        const val EVERY_MS = 4 * 60 * 60 * 1000L
        private const val PACE_MS = 250L
    }
}
