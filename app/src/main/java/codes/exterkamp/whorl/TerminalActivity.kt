package codes.exterkamp.whorl

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalActivity : Activity(), TerminalViewClient, TerminalSessionClient, PromptUi {

    private lateinit var store: Store
    private lateinit var terminalView: TerminalView
    private lateinit var keysBar: View
    private lateinit var endedBar: View
    private lateinit var sessionTitle: TextView
    private lateinit var statusLine: TextView
    private lateinit var sessionDot: View
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView
    private lateinit var session: ActiveSession

    private lateinit var keyboardKey: View

    /** JetBrains Mono, for the terminal text. The chrome around it uses the system font, as Material does. */
    private val font: Typeface by lazy { ResourcesCompat.getFont(this, R.font.jetbrains_mono) ?: Typeface.MONOSPACE }
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // The app's Material colors: the wallpaper's on Android 12 and later, light or dark with the system.
    private val palette by lazy { appColors(this, isDark(this)) }
    private val surfaceColor by lazy { palette.surface.toArgb() }
    private val barColor by lazy { palette.surfaceContainer.toArgb() }
    private val keyColor by lazy { palette.surfaceContainerHighest.toArgb() }
    private val textColor by lazy { palette.onSurface.toArgb() }
    private val mutedColor by lazy { palette.onSurfaceVariant.toArgb() }
    private val accentColor by lazy { palette.primary.toArgb() }
    private val selectedColor by lazy { palette.primaryContainer.toArgb() }
    private val warnColor by lazy { palette.tertiary.toArgb() }

    /** Whether the soft keyboard is up, from the window insets. */
    private var keyboardShown = false

    /** Put away with the keyboard key, so taps on the terminal (a TUI's mouse clicks, say) leave it down. */
    private var keyboardCollapsed = false

    private var ctrlLatched = false
    private var altLatched = false
    private var fontSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        val profile = intent.getStringExtra(EXTRA_PROFILE)?.let { store.profile(it) }
        if (profile == null) {
            finish()
            return
        }
        // Draw edge to edge (Android 15+ enforces it for apps targeting 35+) and pad for the system bars and
        // the keyboard ourselves, so the terminal shrinks above the keyboard on every Android version.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        fontSize = store.fontSize.takeIf { it > 0 } ?: sp(13f)

        applyTerminalColors()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark(this@TerminalActivity)
            isAppearanceLightNavigationBars = !isDark(this@TerminalActivity)
        }

        terminalView = TerminalView(this, null).apply {
            setBackgroundColor(surfaceColor)
            setTerminalViewClient(this@TerminalActivity)
            setTextSize(fontSize)
            setTypeface(font)
            isFocusable = true
            isFocusableInTouchMode = true
            keepScreenOn = true
        }
        keysBar = buildKeysBar()
        endedBar = buildEndedBar()

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                keyboardShown = insets.isVisible(WindowInsetsCompat.Type.ime())
                if (::keyboardKey.isInitialized) keyboardKey.background = keyBackground(keyboardShown)
                WindowInsetsCompat.CONSUMED
            }
            addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(terminalView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(endedBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(keysBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        Sessions.restore(this)
        attach(Sessions.open(this, profile))
    }

    /** Opening a host that is already showing here, or switching from the header, reuses this screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val profile = intent.getStringExtra(EXTRA_PROFILE)?.let { store.profile(it) } ?: return
        attach(Sessions.open(this, profile))
    }

    private fun attach(active: ActiveSession) {
        if (::session.isInitialized && session !== active) {
            unbindListeners()
            // A session that ended and is being switched away from has nothing left to show.
            if (session.finished && session.replacedBy == null) Sessions.remove(this, session)
        }
        session = active
        title = active.profile.label
        terminalView.attachSession(active.terminal)
        showEnded(active.finished)
        refreshHeader()
        if (!isPaused) bindListeners()
        Alerts.clear(this, active.profile.id)
        terminalView.requestFocus()
        terminalView.post { showKeyboard() }
    }

    private var isPaused = true

    override fun onResume() {
        super.onResume()
        isPaused = false
        // Reconnected while this screen was in the background.
        var current = session
        while (true) current = current.replacedBy ?: break
        if (current !== session) attach(current)
        bindListeners()
        Alerts.clear(this, session.profile.id)
        // Catch up on anything that arrived while we weren't listening.
        terminalView.onScreenUpdated()
        showEnded(session.finished)
        refreshHeader()
    }

    private fun bindListeners() {
        session.listener = this
        session.attach(this)
    }

    override fun onPause() {
        super.onPause()
        isPaused = true
        unbindListeners()
    }

    private fun unbindListeners() {
        if (session.listener === this) session.listener = null
        if (session.ui === this) session.attach(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::session.isInitialized && session.finished && isFinishing) Sessions.remove(this, session)
    }

    // ---- Header -----------------------------------------------------------------------------------------------

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(barColor)
        setPadding(dp(4), dp(4), dp(4), dp(4))

        fun iconButton(icon: Int, description: String, onTap: () -> Unit) = ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(textColor)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            background = ripple(oval = true)
            setOnClickListener { onTap() }
        }.also { addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }

        // Home: back to the machine list, leaving this session running.
        iconButton(R.drawable.ic_arrow_back, "Machines") { finish() }

        // The current session; tap to switch between sessions.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = ripple(oval = false)
            sessionDot = View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentColor) }
            }
            addView(sessionDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) })
            sessionTitle = TextView(context).apply {
                typeface = medium
                setTextColor(textColor)
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            statusLine = TextView(context).apply {
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(sessionTitle)
                addView(statusLine)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_arrow_drop_down)
                imageTintList = ColorStateList.valueOf(mutedColor)
            })
            setOnClickListener { showSessionSwitcher(it) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // End this session.
        iconButton(R.drawable.ic_close, "End session") { endSession() }
    }

    private fun refreshHeader() {
        val others = Sessions.all().count { it !== session }
        sessionTitle.text = session.profile.label + if (others > 0) "  ·  +$others" else ""
        val (status, color) = statusOf(session)
        statusLine.text = status
        statusLine.setTextColor(color)
        (sessionDot.background as GradientDrawable).setColor(color)
    }

    /** The header's status line: what the connection is doing right now, and its color. */
    private fun statusOf(s: ActiveSession): Pair<String, Int> {
        val protocol = if (s.profile.protocol == Protocol.MOSH) "mosh" else "SSH"
        val route = s.route
        return when {
            s.finished && s.terminal.exitStatus == EXIT_DROPPED ->
                "Connection lost · reconnects when the network answers" to warnColor
            s.finished && s.automatic && route == null ->
                "Couldn't reconnect · retries on the next network change" to warnColor
            s.finished -> "Disconnected" to mutedColor
            route == null && s.automatic -> "Reconnecting (${s.reason}) · ${s.progress}" to warnColor
            route == null -> s.progress.replaceFirstChar { it.uppercase() } to warnColor
            else -> {
                val port = if (route.port != 22) ":${route.port}" else ""
                "Connected · $protocol via ${route.via} ${route.ip}$port" to accentColor
            }
        }
    }

    private fun showSessionSwitcher(anchor: View) {
        val all = Sessions.all()
        PopupMenu(this, anchor).apply {
            all.forEach { other ->
                val marker = when {
                    other === session -> "● "
                    other.finished -> "○ "
                    else -> "  "
                }
                val state = if (other.finished) "  (ended)" else ""
                menu.add(marker + other.profile.label + state).setOnMenuItemClickListener {
                    if (other !== session) attach(other)
                    true
                }
            }
            menu.add("New session…").setOnMenuItemClickListener { finish(); true }
            show()
        }
    }

    /** End the current session, then show another open one, or go home if there are none. */
    private fun endSession() {
        Sessions.remove(this, session)
        val next = Sessions.all().firstOrNull { !it.finished } ?: Sessions.all().firstOrNull()
        if (next == null) finish() else attach(next)
    }

    // ---- Extra keys -------------------------------------------------------------------------------------------

    private fun buildKeysBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun <V : View> V.place(onTap: (View) -> Unit): V = apply {
            background = keyBackground(false)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTap(it)
            }
            row.addView(this, LinearLayout.LayoutParams(WRAP_CONTENT, dp(42)).apply { marginEnd = dp(6) })
        }
        fun key(label: String, onTap: (View) -> Unit): TextView = TextView(this).apply {
            typeface = medium
            text = label
            setTextColor(textColor)
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
        }.place(onTap)
        fun iconKey(icon: Int, description: String, onTap: (View) -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(textColor)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            minimumWidth = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
        }.place(onTap)

        keyboardKey = iconKey(R.drawable.ic_keyboard, "Show or hide the keyboard") { toggleKeyboard() }
        key("Esc") { sendKey(KeyEvent.KEYCODE_ESCAPE) }
        key("Tab") { sendKey(KeyEvent.KEYCODE_TAB) }
        ctrlKey = key("Ctrl") { ctrlLatched = !ctrlLatched; refreshModifiers() }
        altKey = key("Alt") { altLatched = !altLatched; refreshModifiers() }
        joystick(key(JOYSTICK) {})
        for (c in listOf("-", "/", "|", "~", "`")) key(c) {
            terminalView.resetKeyboardInput()
            terminalView.inputCodePoint(c.codePointAt(0), false, false)
        }.typeface = font
        key("Home") { sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        key("End") { sendKey(KeyEvent.KEYCODE_MOVE_END) }
        key("PgUp") { sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        key("PgDn") { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        iconKey(R.drawable.ic_more_vert, "More") { showMenu(it) }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(barColor)
            addView(row)
        }
    }

    /**
     * One key for all four arrows: press it and drag toward a direction. The arrow is sent once, then repeats for
     * as long as the finger stays there, faster the further it's dragged. Sliding to another side changes direction
     * without lifting.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun joystick(view: TextView) {
        view.contentDescription = "Arrow keys: press and drag toward a direction"
        val deadZone = dp(12)
        val far = dp(56)
        var originX = 0f
        var originY = 0f
        var distance = 0f
        var direction = 0
        val repeat = object : Runnable {
            override fun run() {
                if (direction == 0) return
                sendKey(direction)
                view.postDelayed(this, if (distance > far) 35L else 90L)
            }
        }
        fun release() {
            direction = 0
            view.removeCallbacks(repeat)
            view.text = JOYSTICK
            view.background = keyBackground(false)
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    originX = event.rawX
                    originY = event.rawY
                    // Keep the scrolling key bar from taking a sideways drag.
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    v.background = keyBackground(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - originX
                    val dy = event.rawY - originY
                    distance = kotlin.math.hypot(dx, dy)
                    val next = when {
                        distance < deadZone -> 0
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) -> if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                        else -> if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                    }
                    if (next != direction) {
                        direction = next
                        view.removeCallbacks(repeat)
                        view.text = ARROWS[next] ?: JOYSTICK
                        if (next != 0) {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            sendKey(next)
                            view.postDelayed(repeat, 350L)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
            }
            true
        }
    }

    private fun sendKey(keyCode: Int) {
        if (!session.terminal.isRunning) return
        terminalView.resetKeyboardInput()
        var mods = 0
        if (readControlKey()) mods = mods or KeyHandler.KEYMOD_CTRL
        if (readAltKey()) mods = mods or KeyHandler.KEYMOD_ALT
        terminalView.handleKeyCode(keyCode, mods)
    }

    private fun refreshModifiers() {
        ctrlKey.background = keyBackground(ctrlLatched)
        altKey.background = keyBackground(altLatched)
        // End any word the keyboard is composing, so ctrl-C doesn't become the start of a word it later revises.
        getSystemService(InputMethodManager::class.java).restartInput(terminalView)
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Paste").setOnMenuItemClickListener { paste(); true }
            menu.add("Bigger text").setOnMenuItemClickListener { changeFontSize(+1); true }
            menu.add("Smaller text").setOnMenuItemClickListener { changeFontSize(-1); true }
            menu.add("Light terminal in light mode").apply {
                isCheckable = true
                isChecked = store.terminalFollowsSystem
                setOnMenuItemClickListener {
                    store.terminalFollowsSystem = !store.terminalFollowsSystem
                    applyTerminalColors()
                    true
                }
            }
            menu.add("Disconnect").setOnMenuItemClickListener { endSession(); true }
            show()
        }
    }

    private fun buildEndedBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(barColor)
        setPadding(dp(16), dp(6), dp(8), dp(6))
        addView(TextView(context).apply {
            text = "Disconnected"
            textSize = 15f
            setTextColor(textColor)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fun button(label: String, onTap: () -> Unit) = addView(TextView(context).apply {
            typeface = medium
            text = label
            textSize = 14f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(12), 0, dp(12), 0)
            background = ripple(oval = false)
            setOnClickListener { onTap() }
        })
        button("Reconnect") { Sessions.reconnect(this@TerminalActivity, session, reason = null) }
        button("Close") { endSession() }
    }

    private fun showEnded(ended: Boolean) {
        endedBar.visibility = if (ended) View.VISIBLE else View.GONE
        keysBar.visibility = if (ended) View.GONE else View.VISIBLE
    }

    /** Dark, or light in light mode if the user chose that; sessions already open take the new colors too. */
    private fun applyTerminalColors() {
        if (TerminalTheme.apply(this)) {
            Sessions.all().forEach { it.terminal.emulator?.mColors?.reset() }
            if (::terminalView.isInitialized) terminalView.invalidate()
        }
    }

    private fun changeFontSize(direction: Int) {
        fontSize = (fontSize + direction * dp(1)).coerceIn(sp(7f), sp(32f))
        terminalView.setTextSize(fontSize)
        store.fontSize = fontSize
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        if (session.terminal.isRunning) {
            terminalView.resetKeyboardInput()
            session.terminal.emulator?.paste(text)
        }
    }

    private fun showKeyboard() {
        if (keyboardCollapsed) return
        getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, 0)
    }

    /** The keyboard key: put the keyboard away for more terminal, or bring it back. */
    private fun toggleKeyboard() {
        keyboardCollapsed = keyboardShown
        if (keyboardCollapsed) {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(terminalView.windowToken, 0)
        } else {
            terminalView.requestFocus()
            showKeyboard()
        }
    }

    // ---- PromptUi -----------------------------------------------------------------------------------------------

    override fun replaced(next: ActiveSession) = attach(next)

    override fun refresh() = refreshHeader()

    override fun askYesNo(title: String, message: String, answer: (Boolean) -> Unit) {
        var answered = false
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> answered = true; answer(true) }
            .setNegativeButton("No") { _, _ -> answered = true; answer(false) }
            .setOnDismissListener { if (!answered) answer(false) }
            .show()
    }

    override fun askSecret(title: String, message: String, answer: (String?) -> Unit) {
        var answered = false
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = message.trim().removeSuffix(":")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(LinearLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            })
            .setPositiveButton("OK") { _, _ -> answered = true; answer(input.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> answered = true; answer(null) }
            .setOnDismissListener { if (!answered) answer(null) }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.setOnEditorActionListener { _, _, _ ->
            answered = true
            answer(input.text.toString())
            dialog.dismiss()
            true
        }
        dialog.show()
        input.requestFocus()
    }

    // ---- TerminalSessionClient ----------------------------------------------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession) = terminalView.onScreenUpdated()

    override fun onTitleChanged(changedSession: TerminalSession) {
        changedSession.title?.let { title = it }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        showEnded(true)
        refreshHeader()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) = paste()

    override fun onBell(session: TerminalSession) {
        terminalView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onColorsChanged(session: TerminalSession) = terminalView.invalidate()

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int? = null

    // ---- TerminalViewClient -------------------------------------------------------------------------------------

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            changeFontSize(if (scale > 1f) +1 else -1)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        terminalView.requestFocus()
        showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape() = false

    // False lets keyboards compose words (swipe typing); TerminalView mirrors the composing text as it changes.
    override fun shouldEnforceCharBasedInput() = false

    override fun shouldUseCtrlSpaceWorkaround() = false

    override fun isTerminalViewSelected() = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    /** Long-press on a URL offers to open or copy it; anywhere else falls through to text selection. */
    override fun onLongPress(event: MotionEvent): Boolean {
        val emulator = session.terminal.emulator ?: return false
        val (column, row) = terminalView.getColumnAndRow(event, true).let { it[0] to it[1] }
        val top = terminalView.topRow
        val url = Links.at(emulator.screen, emulator.mColumns, top, top + emulator.mRows - 1, row, column) ?: return false
        terminalView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showLinkMenu(url, event)
        return true
    }

    private fun showLinkMenu(url: String, event: MotionEvent) {
        // Anchor the menu where the finger is.
        val anchor = View(this)
        val root = window.decorView as android.view.ViewGroup
        root.addView(anchor, android.widget.FrameLayout.LayoutParams(1, 1).apply {
            val location = IntArray(2).also { terminalView.getLocationInWindow(it) }
            leftMargin = location[0] + event.x.toInt()
            topMargin = location[1] + event.y.toInt()
        })
        PopupMenu(this, anchor).apply {
            menu.add(url).isEnabled = false
            menu.add("Open link").setOnMenuItemClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                }
                true
            }
            menu.add("Copy link").setOnMenuItemClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", url))
                true
            }
            setOnDismissListener { root.removeView(anchor) }
            show()
        }
    }

    /** A latched modifier applies to exactly one key, then releases. */
    override fun readControlKey(): Boolean {
        val down = ctrlLatched
        if (down) { ctrlLatched = false; refreshModifiers() }
        return down
    }

    override fun readAltKey(): Boolean {
        val down = altLatched
        if (down) { altLatched = false; refreshModifiers() }
        return down
    }

    override fun readShiftKey() = false

    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Rounded tonal keys; a latched modifier, or the keyboard key while the keyboard is up, is filled. */
    private fun keyBackground(active: Boolean): Drawable = RippleDrawable(
        ColorStateList.valueOf(palette.onSurface.copy(alpha = 0.12f).toArgb()),
        GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (active) selectedColor else keyColor)
        },
        null,
    )

    /** The touch ripple Material buttons have. */
    private fun ripple(oval: Boolean): Drawable = RippleDrawable(
        ColorStateList.valueOf(palette.onSurface.copy(alpha = 0.12f).toArgb()),
        null,
        GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(8).toFloat()
            setColor(Color.WHITE)
        },
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics).toInt()

    companion object {
        private const val EXTRA_PROFILE = "profile"
        private const val JOYSTICK = "✥"
        private val ARROWS = mapOf(
            KeyEvent.KEYCODE_DPAD_LEFT to "←", KeyEvent.KEYCODE_DPAD_RIGHT to "→",
            KeyEvent.KEYCODE_DPAD_UP to "↑", KeyEvent.KEYCODE_DPAD_DOWN to "↓",
        )

        fun intent(context: Context, profile: Profile) =
            Intent(context, TerminalActivity::class.java).putExtra(EXTRA_PROFILE, profile.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
