package codes.exterkamp.conch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/**
 * Renders each screen to a PNG on the JVM, to look at while changing the UI: ./build.sh recordPaparazziDebug.
 * In the ocean fallback palette; on Android 12 and later the app takes its colors from the wallpaper instead.
 */
class ScreensTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    private val devbox = Profile(
        id = "1", name = "devbox", user = "shane",
        addresses = listOf(Address("192.168.1.174"), Address("100.105.102.51")), startup = "herdr",
    )
    private val pi = Profile(id = "2", name = "", user = "pi", addresses = listOf(Address("raspberrypi.lan")), protocol = Protocol.SSH)
    private val vps = Profile(id = "3", name = "vps", user = "root", addresses = listOf(Address("vps.example.com", 2222)))

    private fun snap(dark: Boolean = false, content: @Composable () -> Unit) {
        if (dark) paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot { MaterialTheme(colorScheme = if (dark) OceanDark else OceanLight) { content() } }
    }

    @Test fun machines() = snap { HostList(listOf(devbox, pi, vps), 0, {}, {}, {}, {}, {}) }

    @Test fun machinesDark() = snap(dark = true) { HostList(listOf(devbox, pi, vps), 0, {}, {}, {}, {}, {}) }

    @Test fun noMachines() = snap { HostList(emptyList(), 0, {}, {}, {}, {}, {}) }

    @Test fun editMachine() = snap { EditHost(devbox, isNew = false, onSave = {}, onDelete = {}, onBack = {}) }

    @Test fun about() = snap { About(onBack = {}) }
}
