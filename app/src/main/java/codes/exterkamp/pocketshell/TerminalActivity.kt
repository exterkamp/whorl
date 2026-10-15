package codes.exterkamp.pocketshell

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var sessionDot: View
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView
    private lateinit var session: ActiveSession

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

        terminalView = TerminalView(this, null).apply {
            setBackgroundColor(BACKGROUND)
            setTerminalViewClient(this@TerminalActivity)
            setTextSize(fontSize)
            setTypeface(Typeface.MONOSPACE)
            isFocusable = true
            isFocusableInTouchMode = true
            keepScreenOn = true
        }
        keysBar = buildKeysBar()
        endedBar = buildEndedBar()

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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
        setBackgroundColor(BAR)
        setPadding(dp(4), 0, dp(4), 0)

        fun iconButton(label: String, description: String, onTap: () -> Unit) = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            text = label
            contentDescription = description
            setTextColor(MUTED)
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = dp(44)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { onTap() }
        }

        // Home: back to the host list, leaving this session running.
        addView(iconButton("⌂", "Machines") { finish() })

        // The current session; tap to switch between sessions.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            sessionDot = View(context).apply {
                background = GradientDrawable().apply { setColor(ACCENT) }
            }
            addView(sessionDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
            sessionTitle = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(FOREGROUND)
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(sessionTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                typeface = Typeface.MONOSPACE
                text = "▾"
                setTextColor(MUTED)
                setPadding(dp(8), 0, 0, 0)
            })
            setOnClickListener { showSessionSwitcher(it) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // End this session.
        addView(iconButton("✕", "End session") { endSession() })
    }

    private fun refreshHeader() {
        val others = Sessions.all().count { it !== session }
        // Which address the connection took, when the machine has more than one.
        val via = session.route?.takeIf { session.profile.addresses.size > 1 }?.let { "  ·  ${it.via}" }.orEmpty()
        sessionTitle.text = session.profile.label + via + if (others > 0) "  ·  +$others" else ""
        (sessionDot.background as GradientDrawable).setColor(if (session.finished) MUTED else ACCENT)
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
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun key(label: String, onTap: (View) -> Unit): TextView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            text = label
            setTextColor(FOREGROUND)
            textSize = 15f
            gravity = Gravity.CENTER
            minWidth = dp(46)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = keyBackground(false)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTap(it)
            }
            row.addView(this, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(4) })
        }

        key("esc") { sendKey(KeyEvent.KEYCODE_ESCAPE) }
        key("tab") { sendKey(KeyEvent.KEYCODE_TAB) }
        ctrlKey = key("ctrl") { ctrlLatched = !ctrlLatched; refreshModifiers() }
        altKey = key("alt") { altLatched = !altLatched; refreshModifiers() }
        key("←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        key("↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        key("↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        key("→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        for (c in listOf("-", "/", "|", "~", "`")) key(c) { terminalView.inputCodePoint(c.codePointAt(0), false, false) }
        key("home") { sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        key("end") { sendKey(KeyEvent.KEYCODE_MOVE_END) }
        key("pgup") { sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        key("pgdn") { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        key("⋮") { showMenu(it) }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(BAR)
            addView(row)
        }
    }

    private fun sendKey(keyCode: Int) {
        if (!session.terminal.isRunning) return
        var mods = 0
        if (readControlKey()) mods = mods or KeyHandler.KEYMOD_CTRL
        if (readAltKey()) mods = mods or KeyHandler.KEYMOD_ALT
        terminalView.handleKeyCode(keyCode, mods)
    }

    private fun refreshModifiers() {
        ctrlKey.background = keyBackground(ctrlLatched)
        altKey.background = keyBackground(altLatched)
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Paste").setOnMenuItemClickListener { paste(); true }
            menu.add("Bigger text").setOnMenuItemClickListener { changeFontSize(+1); true }
            menu.add("Smaller text").setOnMenuItemClickListener { changeFontSize(-1); true }
            menu.add("Disconnect").setOnMenuItemClickListener { endSession(); true }
            show()
        }
    }

    private fun buildEndedBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(BAR)
        setPadding(dp(16), dp(8), dp(8), dp(8))
        addView(TextView(context).apply {
            typeface = Typeface.MONOSPACE
            text = "-- disconnected --"
            setTextColor(FOREGROUND)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        fun button(label: String, onTap: () -> Unit) = addView(TextView(context).apply {
            typeface = Typeface.MONOSPACE
            text = label
            setTextColor(ACCENT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onTap() }
        })
        button("[ reconnect ]") { Sessions.reconnect(this@TerminalActivity, session, automatic = false) }
        button("[ close ]") { endSession() }
    }

    private fun showEnded(ended: Boolean) {
        endedBar.visibility = if (ended) View.VISIBLE else View.GONE
        keysBar.visibility = if (ended) View.GONE else View.VISIBLE
    }

    private fun changeFontSize(direction: Int) {
        fontSize = (fontSize + direction * dp(1)).coerceIn(sp(7f), sp(32f))
        terminalView.setTextSize(fontSize)
        store.fontSize = fontSize
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        if (session.terminal.isRunning) session.terminal.emulator?.paste(text)
    }

    private fun showKeyboard() {
        getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, 0)
    }

    // ---- PromptUi -----------------------------------------------------------------------------------------------

    override fun replaced(next: ActiveSession) = attach(next)

    override fun refresh() = refreshHeader()

    override fun askYesNo(title: String, message: String, answer: (Boolean) -> Unit) {
        var answered = false
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

    // Char-based input keeps IMEs from composing words, which a shell can't undo.
    override fun shouldEnforceCharBasedInput() = true

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

    /** Square keys outlined like a TUI's buttons; a latched modifier lights up. */
    private fun keyBackground(active: Boolean) = GradientDrawable().apply {
        setColor(if (active) ACCENT_DIM else KEY)
        setStroke(dp(1), if (active) ACCENT else KEY_BORDER)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics).toInt()

    companion object {
        private const val EXTRA_PROFILE = "profile"

        // The same phosphor palette as the rest of the app (MainActivity's Colors).
        private val BACKGROUND = Color.parseColor("#050805")
        private val BAR = Color.parseColor("#0B120C")
        private val KEY = Color.parseColor("#0B120C")
        private val KEY_BORDER = Color.parseColor("#1F5A2E")
        private val FOREGROUND = Color.parseColor("#CFF5D6")
        private val MUTED = Color.parseColor("#6F9A78")
        private val ACCENT = Color.parseColor("#00FF41")
        private val ACCENT_DIM = Color.parseColor("#0B3D1A")

        fun intent(context: Context, profile: Profile) =
            Intent(context, TerminalActivity::class.java).putExtra(EXTRA_PROFILE, profile.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
