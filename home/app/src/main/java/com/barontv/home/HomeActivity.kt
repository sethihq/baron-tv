package com.barontv.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.Toast
import com.barontv.home.core.HomeHost
import com.barontv.home.island.IslandView
import com.barontv.home.music.MusicMode
import com.barontv.home.side.SideDockView

/**
 * The home screen: the prototype's Sidebar layout. The slots in activity_home.xml are filled by the parts:
 * the glow, bento, shelf and app dock (this file and home/), the side dock (side/), the island (island/),
 * music mode (music/).
 */
class HomeActivity : Activity(), HomeHost {

    private lateinit var sideDock: SideDockView
    private lateinit var island: IslandView
    private lateinit var musicMode: MusicMode

    // The prototype is drawn on a 960 x 540 dp screen. The TV reports 1280 x 720 at 160 dpi, which would
    // make everything a quarter too small; at 213 dpi it is exactly 960 x 540 dp.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (base.resources.configuration.densityDpi != UI_DPI) {
            applyOverrideConfiguration(Configuration().apply { densityDpi = UI_DPI })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // TODO(home part): glow, bento, shelf, app dock and toast go into their slots here.

        sideDock = SideDockView(this, this)
        findViewById<FrameLayout>(R.id.sideDockSlot).addView(sideDock, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        island = IslandView(this, this)
        findViewById<FrameLayout>(R.id.islandSlot).addView(island, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        musicMode = MusicMode(this, findViewById(R.id.musicSlot))
    }

    override fun onResume() {
        super.onResume()
        musicMode.setForeground(true)
    }

    override fun onPause() {
        musicMode.setForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        musicMode.release()
        super.onDestroy()
    }

    // Every key first goes to music mode: it restarts the idle clock, and while music mode is on the
    // first key only wakes the screen, as a remote does.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && musicMode.onUserActivity()) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && musicMode.onUserActivity()) return true
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Back on API 28 still comes here")
    override fun onBackPressed() {
        // Home is the bottom of the stack: Back only closes what's open on it.
        sideDock.onBack()
    }

    // ---- HomeHost ----

    override fun toast(text: CharSequence) {
        // TODO(home part): the prototype's toast in toastSlot.
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun launch(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        toast("Nothing on this TV opens that")
        false
    } catch (e: SecurityException) {
        toast("Nothing on this TV opens that")
        false
    }

    override fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            toast("That app isn't on this TV")
            return false
        }
        return launch(intent)
    }

    companion object {
        const val UI_DPI = 213
    }
}
