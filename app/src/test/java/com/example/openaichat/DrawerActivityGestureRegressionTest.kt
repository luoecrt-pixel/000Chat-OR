package com.example.openaichat

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.Selection
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** The same Window dispatch route as the device gesture acceptance test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DrawerActivityGestureRegressionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var activity: MainActivity
    private lateinit var drawer: FingerDrawerLayout
    private val trace = StringBuilder()

    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        context.getSharedPreferences("app", 0).edit().clear()
            .putString("provider", "openrouter").putString("model", "anthropic/claude-sonnet-5.5")
            .putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", 0).edit().clear().commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        activity = controller!!.get()
        drawer = field(activity, "drawerLayout") as FingerDrawerLayout
        activity.newChat()
        shadowOf(Looper.getMainLooper()).idle()
        layoutWindow()
    }

    @After fun after() {
        controller?.pause()?.stop()?.destroy()
        context.getSharedPreferences("app", 0).edit().clear().commit()
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    private fun layoutWindow() {
        val metrics = activity.resources.displayMetrics
        activity.window.decorView.apply {
            // Match the configured ViewRoot. A different forced width would
            // legitimately cancel a gesture on the next framework layout frame.
            measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun bounds(view: View): Rect {
        val rect = Rect()
        assertTrue("Gesture view must be visible: $view", view.getLocalVisibleRect(rect))
        val location = IntArray(2)
        view.getLocationInWindow(location)
        rect.offset(location[0], location[1])
        return rect
    }

    private fun record(step: Int, handled: Boolean) {
        trace.append("step=").append(step).append(" handled=").append(handled)
            .append(" fraction=").append(drawer.visibleFraction)
        for (name in listOf("eligible", "dragging", "aborted", "gesturePanelWidth", "gestureContainerWidth")) {
            trace.append(' ').append(name).append('=').append(field(drawer, name))
        }
        trace.append(" drawer=").append(bounds(drawer))
            .append(" surface=").append(bounds(field(activity, "scroll") as View)).append('\n')
    }

    private fun swipe(x0: Float, y0: Float, x1: Float, y1: Float, finishAnimation: Boolean = true) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            if (step > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            val fraction = step / 12f
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                12 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x0 + (x1 - x0) * fraction, y0 + (y1 - y0) * fraction, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { record(step, activity.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        if (finishAnimation) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    @Test fun emptyNewChatVerticalThenHorizontalWindowGesturesOpenAndCloseDrawer() {
        val scroll = field(activity, "scroll") as RecyclerView
        assertEquals(0, scroll.adapter!!.itemCount)
        val chatBounds = bounds(scroll)
        assertTrue("Chat surface must be laid out", chatBounds.width() > 200)
        swipe(chatBounds.exactCenterX(), chatBounds.top + chatBounds.height() * .25f,
            chatBounds.exactCenterX(), chatBounds.top + chatBounds.height() * .75f)
        assertFalse(trace.toString(), drawer.isDrawerVisible(GravityCompat.START))
        swipe(chatBounds.left + chatBounds.width() * .2f, chatBounds.exactCenterY(),
            chatBounds.left + chatBounds.width() * .9f, chatBounds.exactCenterY())
        assertTrue(trace.toString(), drawer.isDrawerOpen(GravityCompat.START))
        val panelBounds = bounds(drawer.getChildAt(1))
        swipe(panelBounds.left + panelBounds.width() * .85f, panelBounds.exactCenterY(),
            panelBounds.left + panelBounds.width() * .1f, panelBounds.exactCenterY())
        assertFalse(trace.toString(), drawer.isDrawerVisible(GravityCompat.START))
    }

    @Test fun horizontalWindowGestureImmediatelyAfterRealRecyclerFlingOpensDrawer() {
        val scroll = field(activity, "scroll") as RecyclerView
        assertEquals(0, scroll.adapter!!.itemCount)
        val chatBounds = bounds(scroll)
        swipe(chatBounds.exactCenterX(), chatBounds.top + chatBounds.height() * .25f,
            chatBounds.exactCenterX(), chatBounds.top + chatBounds.height() * .75f,
            finishAnimation = false)
        assertFalse(trace.toString(), drawer.isDrawerVisible(GravityCompat.START))

        // A device UP leaves the empty vertical RecyclerView SETTLING. Start its
        // actual public fling here so this precondition is deterministic even
        // when the JVM's velocity/OverScroller differs from Android. Do not
        // advance the looper: the next DOWN must arrive before its first frame.
        assertTrue("The actual RecyclerView must accept the vertical fling", scroll.fling(0, 3_000))
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, scroll.scrollState)
        swipe(chatBounds.left + chatBounds.width() * .2f, chatBounds.exactCenterY(),
            chatBounds.left + chatBounds.width() * .9f, chatBounds.exactCenterY())
        assertTrue("A previous vertical fling must not reserve the next horizontal gesture\n$trace",
            drawer.isDrawerOpen(GravityCompat.START))
    }

    private fun populatedRecycler(onLinkClick: () -> Unit = {}): RecyclerView {
        val scroll = field(activity, "scroll") as RecyclerView
        scroll.layoutManager = LinearLayoutManager(activity)
        scroll.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 50
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val label = TextView(parent.context).apply {
                    textSize = 20f
                    text = SpannableString("Открыть ссылку").apply {
                        setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) { onLinkClick() }
                        }, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    movementMethod = LinkMovementMethod.getInstance()
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150)
                }
                return object : RecyclerView.ViewHolder(label) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        layoutWindow()
        return scroll
    }

    @Test fun tappingLinkDuringRealRecyclerFlingOnlyStopsScrollingWithoutOpeningLink() {
        var linkClicks = 0
        val scroll = populatedRecycler { linkClicks++ }
        val link = scroll.layoutManager!!.findViewByPosition(0) as TextView
        val linkBounds = bounds(link)
        val x = linkBounds.left + link.paint.measureText("Открыть") / 2f
        val y = linkBounds.top + link.totalPaddingTop + link.layout.getLineBottom(0) / 2f
        fun tap() {
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }

        // Prove that the coordinates reach the link through the actual Window.
        tap()
        assertEquals("An idle link tap must remain actionable", 1, linkClicks)
        linkClicks = 0
        // LinkMovementMethod can leave a highlighted span after the control tap.
        // Do not accidentally turn this into a pre-existing-selection scenario.
        Selection.removeSelection(link.text as Spannable)
        assertFalse(link.hasSelection())
        assertTrue(scroll.fling(0, 3_000))
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, scroll.scrollState)
        tap()
        assertEquals("A tap during fling stops scrolling without activating the underlying link", 0, linkClicks)
        assertFalse(drawer.isDrawerVisible(GravityCompat.START))
    }

    @Test fun verticalWindowGestureDuringRealRecyclerFlingKeepsDrawerClosed() {
        val scroll = field(activity, "scroll") as RecyclerView
        val chatBounds = bounds(scroll)
        assertTrue(scroll.fling(0, 3_000))
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, scroll.scrollState)
        swipe(chatBounds.exactCenterX(), chatBounds.top + chatBounds.height() * .25f,
            chatBounds.exactCenterX(), chatBounds.top + chatBounds.height() * .75f)
        assertFalse("The RecyclerView keeps a subsequent vertical gesture\n$trace",
            drawer.isDrawerVisible(GravityCompat.START))
    }

    @Test fun populatedRecyclerFlingWithSubSlopJitterStillAllowsHorizontalWindowGesture() {
        val scroll = populatedRecycler()
        val chatBounds = bounds(scroll)
        val x0 = chatBounds.left + chatBounds.width() * .2f
        val y0 = chatBounds.exactCenterY()
        assertTrue("The populated RecyclerView must have actual vertical scroll range", scroll.canScrollVertically(1))
        assertTrue(scroll.fling(0, 3_000))
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, scroll.scrollState)
        val beforeJitterOffset = scroll.computeVerticalScrollOffset()
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int, x: Float, y: Float, time: Long) {
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { record(action, activity.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN, x0, y0, down)
        // Native RecyclerView has already taken over its old fling. Keep the
        // next sub-slop pixel buffered until the user's new intent is known;
        // forwarding it would let the native scroller claim this stream again.
        dispatch(MotionEvent.ACTION_MOVE, x0 + 1f, y0 - 1f, down + 1L)
        assertEquals("Sub-slop jitter must not move the previous vertical scroller",
            beforeJitterOffset, scroll.computeVerticalScrollOffset())
        for (step in 1..12) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            val action = if (step == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            dispatch(action, x0 + chatBounds.width() * .7f * step / 12f,
                y0 - 1f, SystemClock.uptimeMillis())
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertTrue("Sub-slop RecyclerView jitter cannot reserve a later horizontal gesture\n$trace",
            drawer.isDrawerOpen(GravityCompat.START))
    }

    @Test fun explicitChildDisallowAfterFlingDownKeepsOwnershipOfTheSameHorizontalStream() {
        val scroll = field(activity, "scroll") as RecyclerView
        val chatBounds = bounds(scroll)
        val x0 = chatBounds.left + chatBounds.width() * .2f
        val y0 = chatBounds.exactCenterY()
        assertTrue(scroll.fling(0, 3_000))
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, scroll.scrollState)
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y0, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { record(action, activity.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN, x0)
        // A new selection/nested-scroll owner is distinct from the native
        // RecyclerView claim made synchronously while handling the old fling.
        scroll.requestDisallowInterceptTouchEvent(true)
        for (step in 1..12) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            dispatch(if (step == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                x0 + chatBounds.width() * .7f * step / 12f)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertFalse("An explicit new child claim must remain authoritative\n$trace",
            drawer.isDrawerVisible(GravityCompat.START))
    }
}
