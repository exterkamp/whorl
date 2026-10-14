package codes.exterkamp.pocketshell

import android.app.Application
import com.termux.terminal.TerminalColors
import java.util.Properties

class PocketShell : Application() {
    override fun onCreate() {
        super.onCreate()
        // A dark palette that matches the app chrome; programs can still override colors with escape sequences.
        TerminalColors.COLOR_SCHEME.updateWith(Properties().apply {
            mapOf(
                "background" to "#0e1116", "foreground" to "#e6edf3", "cursor" to "#7ee787",
                "color0" to "#484f58", "color1" to "#ff7b72", "color2" to "#3fb950", "color3" to "#d29922",
                "color4" to "#58a6ff", "color5" to "#bc8cff", "color6" to "#39c5cf", "color7" to "#b1bac4",
                "color8" to "#6e7681", "color9" to "#ffa198", "color10" to "#56d364", "color11" to "#e3b341",
                "color12" to "#79c0ff", "color13" to "#d2a8ff", "color14" to "#56d4dd", "color15" to "#ffffff",
            ).forEach { (k, v) -> setProperty(k, v) }
        })
    }
}
