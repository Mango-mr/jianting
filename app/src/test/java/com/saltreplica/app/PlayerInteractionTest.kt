package com.saltreplica.app

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 播放页交互：左滑进歌词、点歌词区进歌词、列表弹出播放队列 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class PlayerInteractionTest {

    private fun launch(): MainActivity {
        PlaybackConnection.autoConnect = false
        return Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun openPlayer(): MainActivity {
        val activity = launch()
        idle()
        activity.openPlayer()
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        return activity
    }

    @Test
    fun swipeLeftOnGestureAreaFiresCallback() {
        val activity = openPlayer()
        val area = activity.findViewById<GestureLayout>(R.id.gesture_area)
        var fired = false
        area.onSwipeLeft = { fired = true }

        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 600f, 400f, 0)
        val up = MotionEvent.obtain(0, 250, MotionEvent.ACTION_UP, 120f, 410f, 0)
        area.dispatchTouchEvent(down)
        area.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()

        assertTrue("左滑应触发回调", fired)
    }

    @Test
    fun verticalDragDoesNotTriggerSwipe() {
        val activity = openPlayer()
        val area = activity.findViewById<GestureLayout>(R.id.gesture_area)
        var fired = false
        area.onSwipeLeft = { fired = true }

        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 400f, 200f, 0)
        val up = MotionEvent.obtain(0, 250, MotionEvent.ACTION_UP, 410f, 600f, 0)
        area.dispatchTouchEvent(down)
        area.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()

        assertTrue("竖向拖动不该当成左滑", !fired)
    }

    @Test
    fun tappingLyricAreaOpensLyricsPage() {
        val activity = openPlayer()
        activity.findViewById<View>(R.id.lyric_box).performClick()
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        assertTrue(
            activity.supportFragmentManager.findFragmentByTag("lyrics") is LyricsFragment
        )
    }

    @Test
    fun listButtonOpensQueueSheet() {
        val activity = openPlayer()
        activity.findViewById<View>(R.id.btn_queue).performClick()
        idle()
        assertNotNull(activity.supportFragmentManager.findFragmentByTag("queue"))
    }

    @Test
    fun modeButtonCyclesThreeStates() {
        val activity = openPlayer()
        val before = Prefs.playMode
        activity.findViewById<View>(R.id.btn_mode).performClick()
        idle()
        assertTrue("点一次应切到下一档", Prefs.playMode == (before + 1) % 3)
    }

    @Test
    fun backButtonPopsBackToLibrary() {
        val activity = openPlayer()
        assertNotNull(activity.supportFragmentManager.findFragmentByTag("player"))
        activity.findViewById<View>(R.id.btn_back).performClick()
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        val current = activity.supportFragmentManager.findFragmentById(R.id.container)
        assertTrue("返回应回到音乐库，而不是留在播放页", current is LibraryFragment)
    }
}
