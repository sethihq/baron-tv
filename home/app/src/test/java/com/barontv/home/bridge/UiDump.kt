package com.barontv.home.bridge

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a `uiautomator dump` (the XML the TV writes with
 * `uiautomator dump /data/local/tmp/ui.xml`) into the nodes ScreenWalk would keep: in document order,
 * the ones that show text or a description. The dump has only visible nodes, as the walk.
 */
object UiDump {
    private val BOUNDS = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    fun parse(xml: String): List<ScreenText> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val out = ArrayList<ScreenText>()
        walk(doc.documentElement, out)
        return out
    }

    fun fixture(name: String): List<ScreenText> = parse(UiDump::class.java.getResource("/bridge/$name")!!.readText())

    private fun walk(e: Element, out: MutableList<ScreenText>) {
        if (e.tagName == "node") {
            val b = BOUNDS.matchEntire(e.getAttribute("bounds"))!!.groupValues.drop(1).map { it.toInt() }
            val cls = e.getAttribute("class")
            val t = ScreenText(
                left = b[0],
                top = b[1],
                right = b[2],
                bottom = b[3],
                text = e.getAttribute("text"),
                desc = e.getAttribute("content-desc"),
                id = e.getAttribute("resource-id"),
                cls = cls,
                seek = cls.endsWith("SeekBar") || cls.endsWith("ProgressBar"),
            )
            if (t.text.isNotBlank() || t.desc.isNotBlank()) out += t
        }
        val children = e.childNodes
        for (i in 0 until children.length) (children.item(i) as? Element)?.let { walk(it, out) }
    }
}
