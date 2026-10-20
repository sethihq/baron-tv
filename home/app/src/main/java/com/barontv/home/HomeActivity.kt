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
import com.barontv.home.core.HomeHost
import com.barontv.home.home.HomeScreen
import com.barontv.home.home.Images
import com.barontv.home.island.IslandView
import com.barontv.home.music.MusicMode
import com.barontv.home.saver.SaverOverlay
import com.barontv.home.side.SideDockView

/**
 * The home screen: the prototype's Sidebar layout. The slots in activity_home.xml are filled by the parts:
 * the glow, bento, shelf and app dock (this file and home/), the side dock (side/), the island (island/),
 * music mode (music/), the screensaver (saver/).
 */
class HomeActivity : Activity(), HomeHost {

    private lateinit var home: HomeScreen
    private lateinit var sideDock: SideDockView
    private lateinit var island: IslandView
    private lateinit var musicMode: MusicMode
    private lateinit var saver: SaverOverlay
    private var resumed = false
    private var musicCovered = false
    private var saverOnResume = false

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
        // The Input key service (bridge, volume bar, music mode over apps) must stay on; see ServiceGuard.
        com.barontv.home.input.ServiceGuard.start(this)
        // The layout's root paints the screen black, so the theme's black window behind it would only be
        // a second full-screen fill on every frame (the launch preview still uses the theme's).
        window.setBackgroundDrawable(null)

        // The glow, bento, shelf, app dock and toast (home/). First, so the toast is there for the others.
        home = HomeScreen(this, this, findViewById(R.id.root))

        sideDock = SideDockView(this, this)
        findViewById<FrameLayout>(R.id.sideDockSlot).addView(sideDock, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        island = IslandView(this, this)
        findViewById<FrameLayout>(R.id.islandSlot).addView(island, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        musicMode = MusicMode(this, findViewById(R.id.musicSlot))
        saver = SaverOverlay(this, findViewById(R.id.saverSlot))
        // Under music mode's black or the screensaver the clock stops ticking: nothing there can be seen.
        // While the screensaver shows, music mode stands aside, so the two never show together.
        musicMode.onCovered = { covered ->
            musicCovered = covered
            home.setCovered(covered || saver.isOn)
        }
        saver.onShown = { shown ->
            home.setCovered(shown || musicCovered)
            musicMode.setForeground(resumed && !shown)
        }

        // Last, once every part is in: the apps, focus on YouTube, then the cached and fresh data.
        home.start()
    }

    // For checking the screensaver without waiting 10 minutes:
    // am start -n com.barontv.home/.HomeActivity --ez show_saver true
    // (A new intent arrives between onPause and onResume, so it's shown once the screen is back in front.)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_SAVER, false)) saverOnResume = true
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        home.resume()
        musicMode.setForeground(true)
        saver.setForeground(true)
        if (saverOnResume) {
            saverOnResume = false
            saver.showNow()
        }
    }

    override fun onPause() {
        resumed = false
        musicMode.setForeground(false)
        saver.setForeground(false)
        home.pause()
        super.onPause()
    }

    override fun onDestroy() {
        musicMode.release()
        saver.release()
        home.destroy()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Out of sight or short of memory: let the decoded-picture cache go (what's on screen stays).
        if (level >= TRIM_MEMORY_UI_HIDDEN) Images.trim()
    }

    // Every key first goes to the screensaver and music mode: each restarts its idle clock, and while
    // either is showing the first key only wakes the screen, as a remote does.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && wake()) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && wake()) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun wake(): Boolean {
        val wokeSaver = saver.onUserActivity()
        val wokeMusic = musicMode.onUserActivity()
        return wokeSaver || wokeMusic
    }

    @Deprecated("Back on API 28 still comes here")
    override fun onBackPressed() {
        // Home is the bottom of the stack: Back only closes what's open on it.
        sideDock.onBack()
    }

    // ---- HomeHost ----

    override fun toast(text: CharSequence) {
        home.toast(text)
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
        private const val EXTRA_SHOW_SAVER = "show_saver"
    }
}
