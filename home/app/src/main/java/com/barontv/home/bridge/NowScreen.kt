package com.barontv.home.bridge

import java.util.Locale
import kotlin.math.abs

/**
 * One node of the screen's accessibility tree that shows something: its text, its description, its view
 * id (empty in a web view), its class, and its bounds in screen pixels. [seek] marks a seek or progress bar.
 */
data class ScreenText(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String = "",
    val desc: String = "",
    val id: String = "",
    val cls: String = "",
    val seek: Boolean = false,
)

/** What could be read off a music app's screen: empty strings and -1 where it couldn't. */
data class ScreenTrack(val title: String = "", val artist: String = "", val positionMs: Long = -1, val durationMs: Long = -1) {
    companion object {
        val NONE = ScreenTrack()
    }
}

/**
 * The song a music app shows, read from its accessibility tree, carefully: a wrong song is worse than
 * none, so it answers only when the screen plainly is a player. In order:
 *
 * 1. Words that say it: "Now playing: <song> by <artist>" in a text or a description.
 * 2. A native player's own view ids: one title-like id (track_title, now_playing_title...) and one
 *    artist-like id (artist, track_subtitle...), with the player on screen (see 3) unless the id itself
 *    says it's the player's.
 * 3. The layout, for a web view with no ids (Spotify's TV app is one). The player is found by its
 *    progress (two times on one row with a bar's width between them: "1:02" and "2:55", or "-1:53") or
 *    its transport (play or pause with next or previous beside it on its row; a playlist's or a banner's
 *    lone Play isn't enough). The song is in the lines just above them (close above, not across the
 *    screen) that overlap them sideways: of the nearest three, the tallest is the title (titles are set
 *    biggest), and the line right under it the artist.
 *    Transport words, times, single letters and "Playing from..." labels are never a line; other button
 *    words (Like, Home, Lyrics...) aren't either when the node is a button, but a song may be called Home.
 *
 * The times come from the progress row however the song was found: the left time is the position, the
 * right one the length, or what's left of it when it starts with a minus.
 */
object NowScreen {

    /** The track, and the nodes it was read from (their indexes), so the caller can read them afresh. */
    class Pick(val track: ScreenTrack, val used: List<Int>)

    /** The song in [nodes], from a window [screenHeight] px tall. */
    fun read(nodes: List<ScreenText>, screenHeight: Int): ScreenTrack = pick(nodes, screenHeight).track

    fun pick(nodes: List<ScreenText>, screenHeight: Int): Pick {
        val items = nodes.mapIndexed { i, n -> Item(i, n) }
        val times = times(items)
        val timeUsed = times?.nodes?.map { it.index }.orEmpty()
        val position = times?.positionMs ?: -1L
        val duration = times?.durationMs ?: -1L

        // 1. Said in words.
        for (item in items) {
            for (s in listOf(item.text, item.desc)) {
                val m = NOW_PLAYING.matchEntire(s) ?: continue
                val title = unquote(m.groupValues[1])
                if (title.isNotEmpty()) {
                    return Pick(ScreenTrack(title, unquote(m.groupValues[2]), position, duration), listOf(item.index) + timeUsed)
                }
            }
        }

        val anchors = anchors(items, times)

        // 2. A native player's ids.
        val titles = items.filter { it.isLine && isTitleId(it.idName) }
        if (titles.size == 1 && (anchors != null || PLAYER_IDS.any { it in titles[0].idName })) {
            val title = titles[0]
            val artist = items.filter { it.isLine && it !== title && isArtistId(it.idName) }.singleOrNull()
            return Pick(
                ScreenTrack(title.label, artist?.label.orEmpty(), position, duration),
                listOfNotNull(title.index, artist?.index) + timeUsed,
            )
        }

        // 3. The lines just above the player.
        if (anchors == null) return Pick(ScreenTrack.NONE, emptyList())
        val top = anchors.minOf { it.n.top }
        val left = anchors.minOf { it.n.left }
        val right = anchors.maxOf { it.n.right }
        // A player with its progress row keeps the song near it; one with only buttons, nearer still.
        val height = if (screenHeight > 0) screenHeight else items.maxOf { it.n.bottom } - items.minOf { it.n.top }
        val reach = height * if (times != null) REACH else REACH_BUTTONS_ONLY
        val anchorSet = anchors.map { it.index }.toSet()
        val candidates = items.filter {
            it.index !in anchorSet && (it.isLine || it.isJoiner) && it.n.bottom <= top + 2 && it.n.right > left && it.n.left < right
        }
        val lines = rows(candidates).sortedWith(compareByDescending<Line> { it.bottom }.thenBy { it.first })
        val block = ArrayList<Line>(3)
        for (line in lines) {
            val prev = block.lastOrNull()
            val gap = if (prev == null) top - line.bottom else prev.top - line.bottom
            val limit = if (prev == null) reach else 1.5f * maxOf(prev.height, line.height)
            if (gap > limit) break
            block += line
            if (block.size == 3) break
        }
        if (block.isEmpty()) return Pick(ScreenTrack(positionMs = position, durationMs = duration), timeUsed)
        // The tallest is the title (on a tie, the upper one), the artist the line right under it.
        var t = 0
        for (i in 1 until block.size) if (block[i].height >= block[t].height) t = i
        val titleLine = block[t]
        val artistLine = block.getOrNull(t - 1)
        var title = titleLine.text
        var artist = artistLine?.text.orEmpty()
        if (artistLine == null) {
            // "Song • Artist" on one line.
            val parts = title.split(" \u2022 ", " \u00B7 ")
            if (parts.size == 2 && parts.all { it.isNotBlank() }) {
                title = parts[0].trim()
                artist = parts[1].trim()
            }
        }
        return Pick(ScreenTrack(title, artist, position, duration), titleLine.indexes + artistLine?.indexes.orEmpty() + timeUsed)
    }

    // ---- The progress row ---------------------------------------------------------------------------------

    private class Times(val positionMs: Long, val durationMs: Long, val nodes: List<Item>)

    private fun times(items: List<Item>): Times? {
        // Both in one label: "1:02 / 2:55".
        for (item in items) {
            val m = TIME_PAIR.matchEntire(item.label) ?: continue
            val p = ms(m.groupValues[1])
            val d = ms(m.groupValues[2])
            if (p in 0..d) return Times(p, d, listOf(item))
        }
        // Two labels on one row with a bar between them; the widest such pair if there are several.
        val labels = items.filter { TIME.matches(it.label) }
        var best: Times? = null
        var bestGap = 0
        for (a in labels) {
            if (negative(a.label)) continue // the time gone is never negative
            for (b in labels) {
                if (b.n.left < a.n.right) continue
                val h = maxOf(a.height, b.height, 1)
                if (abs(a.centreY - b.centreY) * 2 > h) continue
                val gap = b.n.left - a.n.right
                if (gap < 2 * h || gap <= bestGap) continue
                val p = ms(a.label)
                val rest = ms(b.label)
                val d = if (negative(b.label)) p + rest else rest
                if (p < 0 || rest < 0 || d < p) continue
                best = Times(p, d, listOf(a, b))
                bestGap = gap
            }
        }
        return best
    }

    /** "1:02" as 62000, "1:02:03" as 3723000 (a minus is ignored); -1 if it isn't a time. */
    fun ms(time: String): Long {
        var s = 0L
        for (part in time.trim().trimStart(*MINUS).split(':')) {
            val v = part.toLongOrNull() ?: return -1
            s = s * 60 + v
        }
        return s * 1000
    }

    private fun negative(time: String) = time.isNotEmpty() && time[0] in MINUS

    // ---- The player's own parts -------------------------------------------------------------------------

    /** The progress row and the transport: the player's own parts, which the song sits above. */
    private fun anchors(items: List<Item>, times: Times?): List<Item>? {
        val transport = transport(items)
        if (times == null && transport.isEmpty()) return null
        return times?.nodes.orEmpty() + transport
    }

    /**
     * Play or pause with next or previous beside it, on its row: a lone Play elsewhere (a banner's, a
     * playlist's) never counts. With more than one such group, the biggest, then the lowest on screen.
     */
    private fun transport(items: List<Item>): List<Item> {
        val skips = items.filter { it.says(NEXT) || it.says(PREVIOUS) }
        var best = emptyList<Item>()
        for (play in items.filter { it.says(PLAY) }) {
            val reach = 4 * maxOf(play.width, play.height)
            val near = skips.filter { sameRow(it, play) && abs(it.centreX - play.centreX) <= reach }
            if (near.isEmpty()) continue
            val group = listOf(play) + near
            if (group.size > best.size || (group.size == best.size && play.n.top > best[0].n.top)) best = group
        }
        return best
    }

    // ---- Lines of text ----------------------------------------------------------------------------------

    /** Text side by side on one row (an artist and a featured one, say), read left to right as one line. */
    private class Line(parts: List<Item>) {
        val top = parts.minOf { it.n.top }
        val bottom = parts.maxOf { it.n.bottom }
        val height = bottom - top
        val first = parts.minOf { it.index }
        val indexes = parts.map { it.index }
        val text = join(parts.sortedBy { it.n.left }.map { it.label })
    }

    private fun rows(candidates: List<Item>): List<Line> {
        // A link and the text inside it say the same thing in the same place: one is enough.
        val unique = candidates.distinctBy { Triple(it.label, it.n.top / 4, it.n.left / 4) }
        val rows = ArrayList<MutableList<Item>>()
        for (item in unique.sortedBy { it.n.top }) {
            val row = rows.firstOrNull { r -> r.any { sameRow(it, item) } }
            if (row != null) row += item else rows += mutableListOf(item)
        }
        // A comma between two artists joins their row; on its own it's no line.
        return rows.filter { r -> r.any { it.isLine } }.map { Line(it) }
    }

    private fun sameRow(a: Item, b: Item): Boolean {
        val overlap = minOf(a.n.bottom, b.n.bottom) - maxOf(a.n.top, b.n.top)
        return overlap * 2 > minOf(a.height, b.height)
    }

    private fun join(parts: List<String>): String {
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.isNotEmpty() && p.first() !in ",.;:") sb.append(' ')
            sb.append(p)
        }
        return sb.toString()
    }

    private fun unquote(s: String) = s.trim().trim('"', '\u201C', '\u201D', '\'').trim()

    // ---- Ids ----------------------------------------------------------------------------------------------

    private val PLAYER_WORDS = listOf("track", "song", "playing", "player", "media", "metadata")

    /** Ids that are the player's own, wherever they are. */
    private val PLAYER_IDS = listOf("playing", "player")

    private fun isTitleId(name: String) = "title" in name && "subtitle" !in name && PLAYER_WORDS.any { it in name }

    private fun isArtistId(name: String) = "artist" in name || ("subtitle" in name && PLAYER_WORDS.any { it in name })

    // ---- One node, cleaned up -----------------------------------------------------------------------------

    private class Item(val index: Int, val n: ScreenText) {
        val text = clean(n.text)
        val desc = clean(n.desc)

        /** What it shows: its text, else its description. */
        val label = text.ifEmpty { desc }
        val width = n.right - n.left
        val height = n.bottom - n.top
        val centreX = (n.left + n.right) / 2
        val centreY = (n.top + n.bottom) / 2

        /** The id's own name, without the package: "track_title". */
        val idName = n.id.substringAfter(":id/").lowercase(Locale.ROOT)

        private val words = listOf(text, desc).filter { it.isNotEmpty() }.map { it.lowercase(Locale.ROOT) }

        fun says(set: Set<String>) = words.any { it in set }

        /** Could be a title or an artist: words, not a button's, a time or a picture's. */
        val isLine: Boolean = lineLike()

        /** Punctuation between two parts of a line (", " or " & " between artists). */
        val isJoiner: Boolean = label.isNotEmpty() && label.length <= 3 && label.all { it in JOINERS }

        private fun lineLike(): Boolean {
            if (label.length < 2 || label.length > MAX_LINE || n.seek || height <= 0) return false
            if (n.cls.endsWith("Image") || n.cls.endsWith("ImageView")) return false
            if (TIME.matches(label) || TIME_PAIR.matches(label)) return false
            if (label.none { it.isLetterOrDigit() } || label.all { it.isDigit() }) return false
            val lower = label.lowercase(Locale.ROOT)
            if (lower in PLAY || lower in NEXT || lower in PREVIOUS || LABELS.any { lower.startsWith(it) }) return false
            // A button's word only rules out a button (or an icon described in words): a song may be called Home.
            val button = n.cls.endsWith("Button") || text.isEmpty()
            return !(button && lower in CONTROLS)
        }
    }

    private fun clean(s: String) = s.replace(INVISIBLE, "").replace(SPACES, " ").trim()

    private const val JOINERS = ",&;/\u2022\u00B7 "
    private val SPACES = Regex("\\s+")
    private val INVISIBLE = Regex("[\u200B-\u200D\u2060\uFEFF]")
    private val MINUS = charArrayOf('-', '\u2212', '\u2013')
    private val TIME = Regex("^[-\u2212\u2013]?\\d{1,2}(:\\d{2}){1,2}$")
    private val TIME_PAIR = Regex("^(\\d{1,2}(?::\\d{2}){1,2})\\s*/\\s*(\\d{1,2}(?::\\d{2}){1,2})$")
    private val NOW_PLAYING = Regex("^now playing[:,]?\\s+(.+?)\\s+by\\s+(.+)$", RegexOption.IGNORE_CASE)

    private const val MAX_LINE = 200

    /** The nearest line may sit at most this much of the screen's height above the player. */
    private const val REACH = 0.3f
    private const val REACH_BUTTONS_ONLY = 0.15f

    private val PLAY = setOf("play", "pause", "play/pause", "play pause", "resume")
    private val NEXT = setOf("next", "next track", "next song", "skip", "skip next", "skip forward", "skip to next", "skip to next track")
    private val PREVIOUS = setOf(
        "previous", "prev", "previous track", "previous song", "skip back", "skip backward", "skip previous",
        "skip to previous", "skip to previous track",
    )

    /** Labels that start a line of context, never a song's name. */
    private val LABELS = listOf("now playing", "playing from", "up next", "next up", "next:")

    /** A player's other buttons. */
    private val CONTROLS = setOf(
        "shuffle", "enable shuffle", "disable shuffle", "shuffle play", "repeat", "enable repeat", "enable repeat one",
        "disable repeat", "repeat one", "like", "liked", "unlike", "dislike", "save", "saved", "add to liked songs",
        "remove from liked songs", "save to your library", "remove from your library", "add to playlist", "lyrics",
        "queue", "devices", "connect", "connect to a device", "mute", "unmute", "volume", "more", "more options",
        "options", "menu", "back", "close", "home", "search", "browse", "library", "your library", "settings",
        "full screen", "fullscreen", "exit full screen", "video", "audio",
    )
}
