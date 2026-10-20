package com.barontv.home.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** Open-Meteo's forecast as the bento reads it; the fixture is Bengaluru's, saved at 6.15 pm on 20 October. */
class WeatherTest {
    private val forecast = Forecast.parse(javaClass.getResource("/home/open-meteo.json")!!.readText())

    private fun words(next: WeatherNext) = next.words.joinToString(" ") { (text, strong) -> if (strong) "[$text]" else text }

    @Test
    fun readsNowFromTheFixture() {
        val r = forecast.readingAt("2026-10-20T18")!!
        assertEquals(25, r.temp) // 25.1
        assertEquals("Partly cloudy", r.text) // code 2 after dark
        assertEquals("cloud-moon", r.icon)
        assertEquals(29, r.high) // 28.5 rounds up, as JavaScript's Math.round
        assertEquals(21, r.low) // 20.6
        assertEquals(8, r.hours.size)
        assertEquals(WeatherHour("7 pm", 24, "Cloudy", "cloud"), r.hours.first())
        assertEquals(Sky.NIGHT, Weather.sky(r))
    }

    @Test
    fun saysWhatComesNext() {
        // Partly cloudy now, cloudy from 7: as the prototype read it at the time ("Clouds from 7 pm").
        val evening = Weather.next(forecast.readingAt("2026-10-20T18")!!)
        assertEquals("cloud", evening.icon)
        assertEquals("[Clouds] from [7 pm]", words(evening))
        // Later the same forecast still answers, with no fetch: cloudy now, drizzle at 11.
        val late = Weather.next(forecast.readingAt("2026-10-20T20")!!)
        assertEquals("cloud-rain", late.icon)
        assertEquals("[Drizzle] from [11 pm]", words(late))
    }

    private fun reading(icon: String, text: String, vararg hours: Pair<String, String>) = WeatherReading(
        temp = 24, text = text, icon = icon, high = 28, low = 20,
        hours = hours.mapIndexed { i, (h, t) -> WeatherHour("${i + 4} pm", 24, t, h) },
    )

    @Test
    fun sentencesForEveryTurn() {
        assertEquals("[Drizzle] from [5 pm]", words(Weather.next(reading("sun", "Clear", "sun" to "Clear", "cloud-rain" to "Drizzle"))))
        assertEquals("[Clear] till late", words(Weather.next(reading("sun", "Clear", "sun" to "Clear", "cloud-sun" to "Partly cloudy"))))
        assertEquals("[Cloudy] till late", words(Weather.next(reading("cloud", "Cloudy", "cloud" to "Cloudy", "cloud-fog" to "Foggy"))))
        assertEquals("[Rain] all evening", words(Weather.next(reading("cloud-rain", "Light rain", "cloud-rain" to "Rain"))))
        assertEquals("[Dry] from [4 pm]", words(Weather.next(reading("cloud-rain", "Rain", "cloud" to "Cloudy"))))
        assertEquals("[Clearing] from [5 pm]", words(Weather.next(reading("cloud", "Cloudy", "cloud" to "Cloudy", "moon" to "Clear"))))
        assertEquals("[Storms] from [4 pm]", words(Weather.next(reading("sun", "Clear", "cloud-lightning" to "Thunderstorms"))))
        assertEquals("[Downpour] from [4 pm]", words(Weather.next(reading("cloud", "Cloudy", "cloud-rain" to "Heavy showers"))))
    }

    @Test
    fun describesWmoCodes() {
        assertEquals("Clear" to "sun", Weather.describe(0, true))
        assertEquals("Clear" to "moon", Weather.describe(0, false))
        assertEquals("Partly cloudy" to "cloud-sun", Weather.describe(2, true))
        assertEquals("Foggy" to "cloud-fog", Weather.describe(48, true))
        assertEquals("Drizzle" to "cloud-rain", Weather.describe(53, false))
        assertEquals("Light rain" to "cloud-rain", Weather.describe(61, true))
        assertEquals("Snow" to "cloud-snow", Weather.describe(85, true))
        assertEquals("Thunderstorms" to "cloud-lightning", Weather.describe(96, true))
        assertEquals("Weather" to "cloud", Weather.describe(7, true))
    }

    @Test
    fun labelsHours() {
        assertEquals("12 am", Weather.hourLabel("2026-10-21T00:00"))
        assertEquals("12 pm", Weather.hourLabel("2026-10-21T12:00"))
        assertEquals("3 pm", Weather.hourLabel("2026-10-21T15:00"))
        assertEquals("9 am", Weather.hourLabel("2026-10-21T09:00"))
    }

    @Test
    fun skies() {
        assertEquals(Sky.NONE, Weather.sky(null))
        assertEquals(Sky.SUN, Weather.sky(reading("sun", "Clear")))
        assertEquals(Sky.PARTLY, Weather.sky(reading("cloud-sun", "Partly cloudy")))
        assertEquals(Sky.CLOUD, Weather.sky(reading("cloud-fog", "Foggy")))
        assertEquals(Sky.RAIN, Weather.sky(reading("cloud-rain", "Rain")))
        assertEquals(Sky.STORM, Weather.sky(reading("cloud-lightning", "Thunderstorms")))
    }

    @Test
    fun anHourOutsideTheForecastHasNoReading() {
        assertNull(forecast.readingAt("2026-10-25T10"))
    }

    @Test
    fun hourKeyIsBengaluruTime() {
        // 12.45 UTC is 6.15 pm in India.
        assertEquals("2026-10-20T18", Weather.hourKey(1_792_500_300_000L))
    }

    @Test(expected = IOException::class)
    fun refusesSomethingElse() {
        Forecast.parse("{\"error\":true,\"reason\":\"Parameter 'latitude' is out of range\"}")
    }
}
