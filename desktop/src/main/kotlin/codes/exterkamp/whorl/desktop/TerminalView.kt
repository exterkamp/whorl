package codes.exterkamp.whorl.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isShiftHeld
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.termux.terminal.KeyCodes
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Typeface
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The bundled JetBrains Mono, as Skia typefaces. */
object TerminalFonts {
    private fun load(name: String): Typeface {
        val bytes = TerminalFonts::class.java.getResourceAsStream("/fonts/$name")!!.readBytes()
        return FontMgr.default.makeFromData(Data.makeFromBytes(bytes))!!
    }
    val regular: Typeface by lazy { load("JetBrainsMono-Regular.ttf") }
    val bold: Typeface by lazy { load("JetBrainsMono-Bold.ttf") }
}

/** Cell metrics for a font size, in pixels. */
class CellMetrics(sizePx: Float) {
    val font = Font(TerminalFonts.regular, sizePx)
    val boldFont = Font(TerminalFonts.bold, sizePx)
    val width: Float = font.measureTextWidth("W")
    val ascent: Float = -font.metrics.ascent
    val height: Float = ceil(font.metrics.descent - font.metrics.ascent + font.metrics.leading)
}

/** A text selection in terminal coordinates; rows are external (negative ones are in the scrollback). */
data class Selection(val startCol: Int, val startRow: Int, val endCol: Int, val endRow: Int) {
    /** Ordered so the first point comes first in reading order. */
    fun ordered(): Selection =
        if (endRow < startRow || endRow == startRow && endCol < startCol) Selection(endCol, endRow, startCol, startRow) else this

    fun contains(col: Int, row: Int): Boolean {
        val s = ordered()
        if (row < s.startRow || row > s.endRow) return false
        if (row == s.startRow && col < s.startCol) return false
        if (row == s.endRow && col > s.endCol) return false
        return true
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TerminalView(session: DesktopSession, fontSize: Int, modifier: Modifier = Modifier, focus: FocusRequester = remember { FocusRequester() }) {
    val density = LocalDensity.current
    val metrics = remember(fontSize, density.density) { CellMetrics(fontSize * density.density * 4f / 3f) }
    // Lines scrolled back into the transcript; 0 shows the live screen.
    var scrollBack by remember(session) { mutableIntStateOf(0) }
    var selection by remember(session) { mutableStateOf<Selection?>(null) }
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(0 to 0) }
    val keys = remember { KeyState() }

    LaunchedEffect(session) { focus.requestFocus() }

    fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val emulator = session.emulator ?: return 0 to 0
        val col = floor(x / metrics.width).toInt().coerceIn(0, emulator.mColumns - 1)
        val row = floor(y / metrics.height).toInt().coerceIn(0, emulator.mRows - 1) - scrollBack
        return col to row
    }

    fun copySelection(): Boolean {
        val s = selection?.ordered() ?: return false
        val text = session.emulator?.screen?.getSelectedText(s.startCol, s.startRow, s.endCol, s.endRow) ?: return false
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        selection = null
        return true
    }

    fun paste() {
        val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrNull()
        if (!text.isNullOrEmpty()) session.emulator?.paste(text)
    }

    session.onClipboard = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) }

    // Typed characters, while this terminal has focus (see TypedInput).
    DisposableEffect(session, focused) {
        val handler: (AwtKeyEvent) -> Unit = { e -> typedChar(e, keys, session) { scrollBack = 0; selection = null } }
        if (focused) TypedInput.target = handler
        onDispose { if (TypedInput.target === handler) TypedInput.target = null }
    }

    var dragStart by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { px ->
                size = px.width to px.height
                val cols = max(4, floor(px.width / metrics.width).toInt())
                val rows = max(2, floor(px.height / metrics.height).toInt())
                session.resize(cols, rows)
            }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                val emulator = session.emulator ?: return@onPreviewKeyEvent false
                handleKey(event, keys, session, emulator, ::copySelection, ::paste) { scrollBack = 0; selection = null }
            }
            .onPointerEvent(PointerEventType.Press) { e ->
                focus.requestFocus()
                val change = e.changes.first()
                val (col, row) = cellAt(change.position.x, change.position.y)
                val emulator = session.emulator ?: return@onPointerEvent
                when {
                    e.buttons.isSecondaryPressed -> {
                        // Like Windows Terminal: right-click copies a selection, or pastes.
                        if (!copySelection()) paste()
                    }
                    emulator.isMouseTrackingActive && !e.keyboardModifiers.isShiftHeld ->
                        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col + 1, row + scrollBack + 1, true)
                    else -> {
                        dragStart = col to row
                        selection = null
                    }
                }
            }
            .onPointerEvent(PointerEventType.Move) { e ->
                val start = dragStart ?: return@onPointerEvent
                val change = e.changes.first()
                val (col, row) = cellAt(change.position.x, change.position.y)
                if (col to row != start || selection != null) selection = Selection(start.first, start.second, col, row)
            }
            .onPointerEvent(PointerEventType.Release) { e ->
                val emulator = session.emulator
                if (dragStart == null && emulator != null && emulator.isMouseTrackingActive && e.button == PointerButton.Primary) {
                    val (col, row) = cellAt(e.changes.first().position.x, e.changes.first().position.y)
                    emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col + 1, row + scrollBack + 1, false)
                }
                dragStart = null
            }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val emulator = session.emulator ?: return@onPointerEvent
                val delta = e.changes.first().scrollDelta.y
                val lines = if (delta > 0) 3 else -3
                when {
                    emulator.isMouseTrackingActive -> {
                        val (col, row) = cellAt(e.changes.first().position.x, e.changes.first().position.y)
                        val button = if (delta > 0) TerminalEmulator.MOUSE_WHEELDOWN_BUTTON else TerminalEmulator.MOUSE_WHEELUP_BUTTON
                        repeat(3) { emulator.sendMouseEvent(button, col + 1, row + 1, true) }
                    }
                    emulator.isAlternateBufferActive -> {
                        // Full-screen programs without mouse support (less, man) get arrow keys instead.
                        val key = if (delta > 0) KeyCodes.KEYCODE_DPAD_DOWN else KeyCodes.KEYCODE_DPAD_UP
                        val code = KeyHandler.getCode(key, 0, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
                        repeat(3) { code?.let(session::send) }
                    }
                    else -> scrollBack = (scrollBack - lines).coerceIn(0, emulator.screen.activeTranscriptRows)
                }
            }
    ) {
        @Suppress("UNUSED_EXPRESSION") session.revision // redraw when the screen changes
        val emulator = session.emulator ?: return@Canvas
        drawIntoCanvas { canvas ->
            drawTerminal(canvas.nativeCanvas, emulator, metrics, scrollBack, selection, focused, size.first.toFloat(), size.second.toFloat())
        }
    }
}

/**
 * Compose Desktop only hands key presses to key-event modifiers; the characters they produce (KEY_TYPED, which
 * carries what the keyboard layout, AltGr, dead keys and IMEs actually made) go to text fields. This catches them
 * for whichever terminal has focus.
 */
object TypedInput {
    @Volatile var target: ((AwtKeyEvent) -> Unit)? = null

    init {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            val t = target
            if (e.id == AwtKeyEvent.KEY_TYPED && t != null) {
                t(e)
                true
            } else false
        }
    }
}

/** A character the keyboard produced, to the terminal. */
private fun typedChar(awt: AwtKeyEvent, keys: KeyState, session: DesktopSession, typed: () -> Unit) {
    if (keys.swallowTyped) {
        keys.swallowTyped = false
        return
    }
    val c = awt.keyChar
    // Keys with their own sequences were handled on key down; these are their KEY_TYPED echoes.
    if (c == AwtKeyEvent.CHAR_UNDEFINED || c == '\n' || c == '\r' || c == '\b' || c == '\t' || c.code == 27 || c.code == 127) return
    val text = buildString {
        if (awt.isAltDown && !awt.isControlDown) append('\u001b') // Alt as Meta; AltGr reports Ctrl+Alt
        append(c)
    }
    typed()
    session.send(text)
}

/** What the key handler remembers between events. */
class KeyState {
    /** A key down was used as a shortcut (copy, paste); drop the character AWT types for it next. */
    var swallowTyped = false
}

/** Keyboard to terminal input. Returns true when the key was used. */
private fun handleKey(
    event: KeyEvent,
    keys: KeyState,
    session: DesktopSession,
    emulator: TerminalEmulator,
    copy: () -> Boolean,
    paste: () -> Unit,
    typed: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    keys.swallowTyped = false

    // Clipboard: Ctrl+Shift+C / Ctrl+Shift+V; Ctrl+C copies only when there is a selection; Shift+Insert pastes.
    val shortcut = when {
        event.isCtrlPressed && event.isShiftPressed && event.key == Key.C -> { copy(); true }
        event.isCtrlPressed && event.isShiftPressed && event.key == Key.V -> { paste(); true }
        event.isCtrlPressed && !event.isShiftPressed && event.key == Key.C -> copy()
        event.isShiftPressed && event.key == Key.Insert -> { paste(); true }
        else -> false
    }
    if (shortcut) {
        keys.swallowTyped = true
        return true
    }

    val keyCode = when (event.key) {
        Key.DirectionUp -> KeyCodes.KEYCODE_DPAD_UP
        Key.DirectionDown -> KeyCodes.KEYCODE_DPAD_DOWN
        Key.DirectionLeft -> KeyCodes.KEYCODE_DPAD_LEFT
        Key.DirectionRight -> KeyCodes.KEYCODE_DPAD_RIGHT
        Key.MoveHome -> KeyCodes.KEYCODE_MOVE_HOME
        Key.MoveEnd -> KeyCodes.KEYCODE_MOVE_END
        Key.PageUp -> KeyCodes.KEYCODE_PAGE_UP
        Key.PageDown -> KeyCodes.KEYCODE_PAGE_DOWN
        Key.Insert -> KeyCodes.KEYCODE_INSERT
        Key.Delete -> KeyCodes.KEYCODE_FORWARD_DEL
        Key.Backspace -> KeyCodes.KEYCODE_DEL
        Key.Enter, Key.NumPadEnter -> KeyCodes.KEYCODE_ENTER
        Key.Tab -> KeyCodes.KEYCODE_TAB
        Key.Escape -> KeyCodes.KEYCODE_ESCAPE
        Key.F1 -> KeyCodes.KEYCODE_F1; Key.F2 -> KeyCodes.KEYCODE_F2; Key.F3 -> KeyCodes.KEYCODE_F3
        Key.F4 -> KeyCodes.KEYCODE_F4; Key.F5 -> KeyCodes.KEYCODE_F5; Key.F6 -> KeyCodes.KEYCODE_F6
        Key.F7 -> KeyCodes.KEYCODE_F7; Key.F8 -> KeyCodes.KEYCODE_F8; Key.F9 -> KeyCodes.KEYCODE_F9
        Key.F10 -> KeyCodes.KEYCODE_F10; Key.F11 -> KeyCodes.KEYCODE_F11; Key.F12 -> KeyCodes.KEYCODE_F12
        else -> null
    }
    if (keyCode != null) {
        var mode = 0
        if (event.isShiftPressed) mode = mode or KeyHandler.KEYMOD_SHIFT
        if (event.isCtrlPressed) mode = mode or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed) mode = mode or KeyHandler.KEYMOD_ALT
        val code = when (keyCode) {
            // KeyHandler leaves the plain keys to the caller.
            KeyCodes.KEYCODE_ENTER -> if (event.isAltPressed) "\u001b\r" else "\r"
            KeyCodes.KEYCODE_ESCAPE -> "\u001b"
            KeyCodes.KEYCODE_DEL -> (if (event.isAltPressed) "\u001b" else "") + if (event.isCtrlPressed) "\u0008" else "\u007f"
            else -> KeyHandler.getCode(keyCode, mode, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
                ?: if (keyCode == KeyCodes.KEYCODE_TAB) "\t" else null
        }
        if (code != null) {
            typed()
            session.send(code)
            return true
        }
    }
    // Ctrl+Space is NUL; AWT sends no KEY_TYPED for it.
    if (event.isCtrlPressed && event.key == Key.Spacebar) { typed(); session.send("\u0000"); return true }
    return false
}

/** Draw the screen (or the scrolled-back transcript) with Skia, one run of same-styled cells at a time. */
private fun drawTerminal(
    canvas: org.jetbrains.skia.Canvas,
    emulator: TerminalEmulator,
    m: CellMetrics,
    scrollBack: Int,
    selection: Selection?,
    focused: Boolean,
    widthPx: Float,
    heightPx: Float,
) {
    val palette = emulator.mColors.mCurrentColors
    val reverseVideo = emulator.isReverseVideo
    val background = palette[if (reverseVideo) TextStyle.COLOR_INDEX_FOREGROUND else TextStyle.COLOR_INDEX_BACKGROUND]
    val paint = Paint().apply { isAntiAlias = true }
    paint.color = background or 0xFF000000.toInt()
    canvas.drawRect(Rect.makeWH(widthPx, heightPx), paint)

    val screen = emulator.screen
    val cursorRow = emulator.cursorRow
    val cursorCol = emulator.cursorCol
    val cursorVisible = emulator.shouldCursorBeVisible() && scrollBack == 0
    val selectionColor = 0x664A90A0

    for (screenRow in 0 until emulator.mRows) {
        val row = screenRow - scrollBack
        val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
        val text = line.mText
        val used = line.spaceUsed
        val top = screenRow * m.height
        val baseline = top + m.ascent
        var col = 0
        var i = 0
        val run = StringBuilder()
        while (col < emulator.mColumns) {
            // One cell: its code point, display width and style.
            val cp: Int
            val chars: Int
            if (i < used) {
                val c = text[i]
                if (Character.isHighSurrogate(c) && i + 1 < used) { cp = Character.toCodePoint(c, text[i + 1]); chars = 2 } else { cp = c.code; chars = 1 }
            } else { cp = ' '.code; chars = 0 }
            val width = max(1, WcWidth.width(cp))
            val style = line.getStyle(col)
            val colors = cellColors(style, palette, reverseVideo)
            var fg = colors.first
            var bg = colors.second
            val atCursor = cursorVisible && row == cursorRow && (col == cursorCol || width == 2 && col + 1 == cursorCol)
            if (atCursor && focused && emulator.cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                val cursor = palette[TextStyle.COLOR_INDEX_CURSOR]
                bg = cursor; fg = background
            }
            val left = col * m.width
            val cellWidth = width * m.width
            if (bg != background) {
                paint.color = bg or 0xFF000000.toInt()
                canvas.drawRect(Rect.makeXYWH(left, top, cellWidth, m.height), paint)
            }
            if (selection != null && selection.contains(col, row)) {
                paint.color = selectionColor
                canvas.drawRect(Rect.makeXYWH(left, top, cellWidth, m.height), paint)
            }
            val effect = TextStyle.decodeEffect(style)
            if (cp != ' '.code && cp != 0 && (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
                run.setLength(0)
                run.appendCodePoint(cp)
                // Combining marks that follow belong to this cell.
                var j = i + chars
                while (j < used && WcWidth.width(text, j) <= 0) { run.append(text[j]); j++ }
                val bold = (effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
                val font = if (bold) m.boldFont else m.font
                val dim = (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0
                paint.color = if (dim) dimmed(fg) else fg or 0xFF000000.toInt()
                val italic = (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0
                if (italic) font.skewX = -0.2f
                canvas.drawString(run.toString(), left, baseline, font, paint)
                if (italic) font.skewX = 0f
                if ((effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0) {
                    canvas.drawRect(Rect.makeXYWH(left, baseline + 2f, cellWidth, max(1f, m.height / 16f)), paint)
                }
                if ((effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0) {
                    canvas.drawRect(Rect.makeXYWH(left, top + m.height / 2f, cellWidth, max(1f, m.height / 16f)), paint)
                }
            }
            if (atCursor && !(focused && emulator.cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK)) {
                paint.color = palette[TextStyle.COLOR_INDEX_CURSOR] or 0xFF000000.toInt()
                val thin = max(1.5f, m.width / 6f)
                when {
                    !focused -> {
                        paint.mode = PaintMode.STROKE; paint.strokeWidth = 1f
                        canvas.drawRect(Rect.makeXYWH(left + 0.5f, top + 0.5f, cellWidth - 1f, m.height - 1f), paint)
                        paint.mode = PaintMode.FILL
                    }
                    emulator.cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR ->
                        canvas.drawRect(Rect.makeXYWH(left, top, thin, m.height), paint)
                    else -> canvas.drawRect(Rect.makeXYWH(left, top + m.height - thin, cellWidth, thin), paint)
                }
            }
            col += width
            i += chars
            // Skip combining marks we drew with the base character.
            while (i < used && WcWidth.width(text, i) <= 0) i++
        }
    }
    // Scrollback indicator.
    if (scrollBack > 0) {
        val total = emulator.screen.activeTranscriptRows + emulator.mRows
        val h = emulator.mRows * m.height
        val thumb = max(12f, h * emulator.mRows / total)
        val y = (h - thumb) * (1f - scrollBack.toFloat() / emulator.screen.activeTranscriptRows)
        paint.color = 0x88888888.toInt()
        canvas.drawRect(Rect.makeXYWH(widthPx - 5f, y, 4f, thumb), paint)
    }
}

/** Foreground and background ARGB for a cell's style, as Termux's renderer works them out. */
private fun cellColors(style: Long, palette: IntArray, reverseVideo: Boolean): Pair<Int, Int> {
    var fore = TextStyle.decodeForeColor(style)
    var back = TextStyle.decodeBackColor(style)
    val effect = TextStyle.decodeEffect(style)
    val bold = (effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0
    if ((fore and 0xff000000.toInt()) != 0xff000000.toInt()) {
        if (bold && fore in 0..7) fore += 8
        fore = palette[fore]
    }
    if ((back and 0xff000000.toInt()) != 0xff000000.toInt()) back = palette[back]
    val inverse = reverseVideo xor ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0)
    return if (inverse) back to fore else fore to back
}

private fun dimmed(color: Int): Int {
    val r = (color shr 16 and 0xff) * 2 / 3
    val g = (color shr 8 and 0xff) * 2 / 3
    val b = (color and 0xff) * 2 / 3
    return (0xff shl 24) or (r shl 16) or (g shl 8) or b
}
