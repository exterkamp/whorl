package codes.exterkamp.conch

import android.app.Application

class ConchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Sessions restored at launch get the right colors before any screen shows them.
        TerminalTheme.apply(this)
    }
}
