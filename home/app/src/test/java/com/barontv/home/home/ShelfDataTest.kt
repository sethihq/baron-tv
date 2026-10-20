package com.barontv.home.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The shelf's data: TVmaze's web schedule for the week to 20 October 2026 (India's half and the global
 * half, trimmed to the fields read), the shows' picture lists, and TMDB's shape for the keyed build.
 */
class ShelfDataTest {
    private val prime = "com.amazon.amazonvideo.livingroom"
    private val hotstar = "in.startv.hotstar"
    private val netflix = "com.netflix.ninja"

    private fun fixture(name: String) = javaClass.getResource("/home/$name")!!.readText()

    private val week: List<Tvmaze.Candidate> = (14..20).flatMap { day ->
        listOf("in", "global").flatMap { half -> Tvmaze.parseSchedule(fixture("tvmaze-web-2026-10-$day-$half.json")) }
    }

    private fun backgroundOf(c: Tvmaze.Candidate): String? =
        javaClass.getResource("/home/tvmaze-images-${c.showId}.json")?.readText()?.let { Tvmaze.background(it) }

    @Test
    fun keepsOnlyTheAppsOnThisTv() {
        val ranked = Tvmaze.rank(week, setOf(prime, hotstar))
        assertTrue(ranked.all { it.service == "Prime Video" || it.service == "JioHotstar" })
        // Netflix's TV app can't run here: its shows only count once it's installed.
        assertTrue(Tvmaze.rank(week, setOf(prime, hotstar, netflix)).any { it.service == "Netflix" })
    }

    @Test
    fun ranksTheWeekByWhatsWatched() {
        val ranked = Tvmaze.rank(week, setOf(prime, hotstar))
        // Out: Thursday Night Football (live US sport), and shows in German, Tagalog and Japanese.
        assertEquals(
            listOf("Helluva Boss", "Judy Justice", "Koffee With Karan", "Hometown Giants", "The Traitors Telugu", "Heart Beat"),
            ranked.map { it.name },
        )
        // One entry a show, however many episodes it had.
        assertEquals(ranked.size, ranked.map { it.showId }.toSet().size)
    }

    @Test
    fun picksThreeShowsTheWideOneLandscape() {
        val shelf = Tvmaze.pick(Tvmaze.rank(week, setOf(prime, hotstar)), ::backgroundOf)
        assertEquals(listOf("Helluva Boss", "Judy Justice", "Koffee With Karan"), shelf.map { it.title })
        assertEquals("https://static.tvmaze.com/uploads/images/original_untouched/589/1472898.jpg", shelf[0].image)
        assertTrue(shelf[1].image.contains("medium_portrait"))
        assertEquals("Animation · Comedy · Season 3 · Prime Video", shelf[0].meta)
        assertEquals("Reality · Legal · Prime Video", shelf[1].meta)
        assertEquals("Talk show · JioHotstar", shelf[2].meta)
        assertEquals(listOf(prime, prime, hotstar), shelf.map { it.packageName })
    }

    @Test
    fun withNoLandscapePictureTheBestPosterFillsTheWideSlot() {
        val ranked = Tvmaze.rank(week, setOf(prime, hotstar))
        val shelf = Tvmaze.pick(ranked, { null })
        assertEquals("Helluva Boss", shelf[0].title)
        assertEquals(ranked[0].posterLarge, shelf[0].image)
    }

    @Test
    fun fewerThanThreeShowsIsNoShelf() {
        val ranked = Tvmaze.rank(Tvmaze.parseSchedule(fixture("tvmaze-web-2026-10-20-global.json")), setOf(prime, hotstar))
        assertEquals(1, ranked.size) // Judy Justice: one show on this TV's apps today
        assertTrue(Tvmaze.pick(ranked, ::backgroundOf).isEmpty())
    }

    @Test
    fun findsALandscapePicture() {
        assertNull(Tvmaze.background(fixture("tvmaze-images-7316.json")))
        assertNull(Tvmaze.background("not json"))
        // Three backgrounds, none main: the 1920 one, not the 3840s.
        assertEquals(
            "https://static.tvmaze.com/uploads/images/original_untouched/443/1109363.jpg",
            Tvmaze.background(fixture("tvmaze-images-57806.json")),
        )
        val main = """[{"type":"background","main":false,"resolutions":{"original":{"url":"https://a/small.jpg","width":1280}}},
            {"type":"background","main":true,"resolutions":{"original":{"url":"https://a/main.jpg","width":3840}}}]"""
        assertEquals("https://a/main.jpg", Tvmaze.background(main))
    }

    @Test(expected = IOException::class)
    fun refusesABrokenSchedule() {
        Tvmaze.parseSchedule("<html>")
    }

    @Test
    fun theShelfCacheRoundTrips() {
        val data = ShelfData(
            ShelfData.Source.TVMAZE,
            Tvmaze.pick(Tvmaze.rank(week, setOf(prime, hotstar)), ::backgroundOf),
        )
        assertEquals(data, ShelfData.fromJson(data.toJson()))
    }

    @Test(expected = IOException::class)
    fun aBrokenCacheIsRefused() {
        ShelfData.fromJson("{\"source\":\"NOPE\",\"items\":[]}")
    }

    @Test
    fun tmdbPicksTheFeaturedBackdropThenTwoPosters() {
        val discover = JSONObject(
            """{"results":[
              {"id":1,"title":"Quiet","popularity":10.0,"genre_ids":[18],"backdrop_path":null,"poster_path":"/q.jpg"},
              {"id":2,"title":"Loud","popularity":99.0,"genre_ids":[28],"backdrop_path":null,"poster_path":"/l.jpg"},
              {"id":3,"title":"Wide","popularity":50.0,"genre_ids":[],"backdrop_path":"/w.jpg","poster_path":"/wp.jpg"},
              {"id":4,"title":"Bare","popularity":70.0,"genre_ids":[],"backdrop_path":null,"poster_path":null}
            ]}""",
        )
        val titles = Tmdb.titles(discover, "movie")
        assertEquals(28, titles[1].genreId)
        assertNull(titles[3].poster)
        // The most popular with a backdrop leads; then the most popular two with posters.
        assertEquals(listOf("Wide", "Loud", "Quiet"), Tmdb.pick(titles).map { it.title })
    }
}
