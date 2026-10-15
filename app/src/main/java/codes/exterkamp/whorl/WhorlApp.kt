package codes.exterkamp.whorl

import android.app.Application

class WhorlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Sessions restored at launch get the right colors before any screen shows them.
        TerminalTheme.apply(this)
    }
}
