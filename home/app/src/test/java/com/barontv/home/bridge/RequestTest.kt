package com.barontv.home.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTest {

    private fun parse(line: String) = Request.parse(line)!!

    @Test
    fun readsTheMethodPathAndQuery() {
        val r = parse("GET /volume?step=up HTTP/1.1")
        assertEquals("GET", r.method)
        assertEquals("/volume", r.path)
        assertEquals("up", r["step"])
        assertEquals(mapOf("step" to "up"), r.query)
    }

    @Test
    fun aPathWithNoQueryHasNoParameters() {
        val r = parse("GET /now HTTP/1.1")
        assertEquals("/now", r.path)
        assertTrue(r.query.isEmpty())
        assertEquals("/", parse("GET / HTTP/1.1").path)
    }

    @Test
    fun queriesAreUrlDecoded() {
        assertEquals("HDMI 1", parse("GET /source?name=HDMI%201 HTTP/1.1")["name"])
        // Forms send a space as '+'.
        assertEquals("HDMI 1", parse("GET /source?name=HDMI+1 HTTP/1.1")["name"])
        // An escaped comma is still the order's separator once decoded.
        assertEquals("a.b,c.d", parse("GET /dock?order=a.b%2Cc.d HTTP/1.1")["order"])
        // UTF-8 escapes.
        assertEquals("Café", parse("GET /source?name=Caf%C3%A9 HTTP/1.1")["name"])
        // '+' in a path is a plus, not a space.
        assertEquals("/a+b", parse("GET /a+b HTTP/1.1").path)
    }

    @Test
    fun aBrokenEscapeStaysAsWritten() {
        assertEquals("100%", parse("GET /x?v=100% HTTP/1.1")["v"])
        assertEquals("%zz", parse("GET /x?v=%zz HTTP/1.1")["v"])
        assertEquals("a%2", parse("GET /x?v=a%2 HTTP/1.1")["v"])
    }

    @Test
    fun emptyValuesRepeatsAndOddPairs() {
        val r = parse("GET /dock?order=&x&step=up&step=down&&=v HTTP/1.1")
        assertTrue(r.has("order"))
        assertEquals("", r["order"])
        assertEquals("", r["x"])
        // The first of a repeated parameter wins.
        assertEquals("up", r["step"])
        assertFalse(r.has(""))
    }

    @Test
    fun aTrailingSlashIsTheSamePath() {
        assertEquals("/input", parse("GET /input/ HTTP/1.1").path)
        assertEquals("/input", parse("GET /input/?keycode=4 HTTP/1.1").path)
        assertEquals("4", parse("GET /input/?keycode=4 HTTP/1.1")["keycode"])
    }

    @Test
    fun aFullUrlCountsOnlyItsPathAndAFragmentIsDropped() {
        val r = parse("GET http://192.168.1.27:5512/launch?package=com.spotify.tv.android HTTP/1.1")
        assertEquals("/launch", r.path)
        assertEquals("com.spotify.tv.android", r["package"])
        assertEquals("/now", parse("GET /now#top HTTP/1.1").path)
    }

    @Test
    fun theMethodIsUpperCasedAndTheVersionIsOptional() {
        assertEquals("GET", parse("get /now").method)
        assertEquals("/now", parse("GET /now").path)
        assertEquals("POST", parse("POST /dock?order=a HTTP/1.1").method)
    }

    @Test
    fun notARequestLine() {
        assertNull(Request.parse(""))
        assertNull(Request.parse("GET"))
        assertNull(Request.parse("hello there"))
        assertNull(Request.parse("G3T /now HTTP/1.1"))
    }
}
