package com.barontv.home.side

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import com.barontv.home.R
import com.barontv.home.core.HomeHost
import java.util.Locale

/** One input as the dock shows it. [state] is TvInputManager's INPUT_STATE_*, or [Inputs.UNKNOWN]. */
internal data class TvInput(val id: String, val label: String, val hdmi: Boolean, val state: Int)

/**
 * The inputs' rules, free of Android types so they can be tested on the JVM: the order and names the
 * dock shows, which state means what, and how the old launcher's proven CVTE switch is sent.
 */
internal object Inputs {
    // TvInputManager.INPUT_STATE_*.
    const val CONNECTED = 0
    const val STANDBY = 1
    const val DISCONNECTED = 2
    const val UNKNOWN = -1

    // CVTE's switch, from launcher/app/src/main/res/values/config.xml: nothing on this TV answers the
    // standard passthrough VIEW intent, so this goes by name to each receiver of it, with the input's
    // hardware number (3 AV, 4 HDMI 1, 5 HDMI 2) in sourceId, twice: the player it opens only switches on
    // the intent after the one that started it.
    const val ACTION = "com.cvte.intent.ACTION_TUNE_SOURCE_OR_CHANNEL"
    const val CATEGORY = "com.cvte.intent.CATEGORY_TUNE_SOURCE_OR_CHANNEL"
    const val EXTRA = "sourceId"
    const val REPEAT = 2

    /** The hardware number at the end of a hardware input's ID, e.g. ".../HDMIInputService/HW4". */
    private val HW_NUMBER = Regex("/HW(\\d+)$")
    private val HDMI_NUMBER = Regex("(?i)\\bHDMI[\\s_-]*(\\d+)")

    fun hwNumber(inputId: String): Int? = HW_NUMBER.find(inputId)?.groupValues?.get(1)?.toIntOrNull()

    /** One broadcast of the switch: which receiver, and the sourceId it carries (none if the ID has no HW number). */
    data class Send(val pkg: String, val cls: String, val sourceId: Int?)

    /** The switch's broadcasts in the order they go: every receiver, then every receiver again. */
    fun sends(inputId: String, receivers: List<Pair<String, String>>, repeat: Int = REPEAT): List<Send> {
        val id = hwNumber(inputId)
        return List(repeat * receivers.size) { n ->
            val (pkg, cls) = receivers[n % receivers.size]
            Send(pkg, cls, id)
        }
    }

    /** What TvInputManager says about one input, before the dock's rules. */
    data class Raw(val id: String, val label: String?, val hdmi: Boolean, val passthrough: Boolean, val state: Int)

    /**
     * The inputs the dock lists: passthrough ones only (HDMI, AV), HDMI first in port order, then the
     * rest, as the old launcher's Inputs menu. Some TVs report every HDMI port as connected whether or not
     * anything is plugged in; then the HDMI states are dropped, as the launcher does.
     */
    fun arrange(raw: List<Raw>): List<TvInput> {
        val pass = raw.filter { it.passthrough }
        val hdmi = pass.filter { it.hdmi }
        val bogus = hdmi.size > 1 && hdmi.all { it.state == CONNECTED }
        return pass
            .map { TvInput(it.id, label(it.label, it.hdmi, it.id), it.hdmi, if (it.hdmi && bogus) UNKNOWN else it.state) }
            .sortedWith(compareBy<TvInput> { !it.hdmi }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** "HDMI1" or "hdmi-1" reads "HDMI 1", composite reads "AV", as the prototype names them. */
    fun label(raw: String?, hdmi: Boolean, id: String): String {
        var l = raw?.trim().orEmpty()
        if (l.isEmpty()) l = if (hdmi) "HDMI" else id
        if (hdmi && !l.uppercase(Locale.ROOT).contains("HDMI")) l = "HDMI $l"
        l = HDMI_NUMBER.replace(l) { "HDMI ${it.groupValues[1]}" }
        if (!hdmi && l.uppercase(Locale.ROOT) in setOf("COMPOSITE", "CVBS")) l = "AV"
        return l
    }

    /** Connected gets the green dot; standby or not reported shows plainly; disconnected is dimmed. */
    fun live(input: TvInput) = input.state == CONNECTED

    fun idle(input: TvInput) = input.state == DISCONNECTED

    fun glyph(input: TvInput): Int = when {
        !input.hdmi -> R.drawable.ph_television_simple
        live(input) -> R.drawable.ph_plugs_connected
        else -> R.drawable.ph_plug
    }

    /**
     * Baron's own three, shown until TvInputManager lists any (at boot the inputs can register after Home
     * is up). The IDs are stand-ins: only their HW numbers are read, which is all the CVTE switch needs.
     */
    val BARON = listOf(
        TvInput("com.mediatek.tvinput/.hdmi.HDMIInputService/HW4", "HDMI 1", true, UNKNOWN),
        TvInput("com.mediatek.tvinput/.hdmi.HDMIInputService/HW5", "HDMI 2", true, UNKNOWN),
        TvInput("com.mediatek.tvinput/.composite.CompositeInputService/HW3", "AV", false, UNKNOWN),
    )
}

/**
 * The TV's inputs from TvInputManager, read on the dock's worker thread, and the switch to one, ported
 * from the old launcher's openInput: the standard passthrough VIEW intent if anything answers it, else
 * CVTE's action as an activity, else as a broadcast to each of its receivers by name.
 */
internal class TvInputSource(private val context: Context, private val worker: SideWorker) {
    // Fetched on first use: the snapshot tests' stand-in Android has no TV input service.
    private val manager by lazy { context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager }
    private var callback: TvInputManager.TvInputCallback? = null

    fun load(done: (List<TvInput>) -> Unit) {
        val m = manager
        if (m == null) {
            done(Inputs.BARON)
            return
        }
        worker.post {
            val raw = try {
                m.tvInputList.map {
                    Inputs.Raw(it.id, it.loadLabel(context)?.toString(), it.type == TvInputInfo.TYPE_HDMI, it.isPassthroughInput, m.getInputState(it.id))
                }
            } catch (e: RuntimeException) {
                emptyList()
            }
            val list = Inputs.arrange(raw).ifEmpty { Inputs.BARON }
            worker.toMain { done(list) }
        }
    }

    /** Follows inputs appearing, going and being plugged in, while the dock is on screen. */
    fun listen(onChange: () -> Unit) {
        val m = manager ?: return
        if (callback != null) return
        val cb = object : TvInputManager.TvInputCallback() {
            override fun onInputStateChanged(inputId: String, state: Int) = onChange()
            override fun onInputAdded(inputId: String) = onChange()
            override fun onInputRemoved(inputId: String) = onChange()
            override fun onInputUpdated(inputId: String) = onChange()
        }
        m.registerCallback(cb, worker.main)
        callback = cb
    }

    fun stopListening() {
        callback?.let { manager?.unregisterCallback(it) }
        callback = null
    }

    @SuppressLint("QueryPermissionsNeeded") // Android 9 has no package visibility rules: every receiver is seen
    fun switchTo(input: TvInput, host: HomeHost) {
        worker.post {
            val pm = context.packageManager
            val uri = TvContract.buildChannelUriForPassthroughInput(input.id)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, TvContract.Channels.CONTENT_ITEM_TYPE)
            if (pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                worker.toMain { host.launch(view) }
                return@post
            }
            val action = Intent(Inputs.ACTION).addCategory(Inputs.CATEGORY)
            val asActivity = Intent(action).setDataAndType(uri, TvContract.Channels.CONTENT_ITEM_TYPE)
            if (pm.resolveActivity(asActivity, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                worker.toMain { host.launch(asActivity) }
                return@post
            }
            // CVTE switches in a receiver whose filter takes no data, and Android 8+ keeps implicit
            // broadcasts from receivers declared in a manifest, so it goes to each one by name.
            val receivers = pm.queryBroadcastReceivers(action, 0)
            if (receivers.isEmpty()) {
                worker.toMain { host.toast(context.getString(R.string.side_not_available)) }
                return@post
            }
            action.setData(uri).addFlags(Intent.FLAG_RECEIVER_FOREGROUND) // someone is waiting on the switch
            for (s in Inputs.sends(input.id, receivers.map { it.activityInfo.packageName to it.activityInfo.name })) {
                val one = Intent(action).setClassName(s.pkg, s.cls)
                if (s.sourceId != null) one.putExtra(Inputs.EXTRA, s.sourceId)
                context.sendBroadcast(one)
            }
        }
    }
}
