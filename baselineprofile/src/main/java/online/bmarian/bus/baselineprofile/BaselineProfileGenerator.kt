package online.bmarian.bus.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

const val PACKAGE = "online.bmarian.bus"
private const val WAIT_MS = 15_000L

/** Needs an English emulator with a location near Cluj stops. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        for (permission in listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS")) {
            device.executeShellCommand("pm grant $packageName android.permission.$permission")
        }
        pressHome()
        startActivityAndWait()

        await(By.desc("Nearby stops")).click()
        await(By.textContains("min walk")).click()

        val departure = device.wait(Until.findObject(By.text(Pattern.compile("\\d+ min|now"))), WAIT_MS)
        if (departure != null) {
            departure.click()
            await(By.desc("Map")).click()
            device.waitForIdle()
            Thread.sleep(3_000)
            await(By.desc("Timetable")).click()
            scrollDown()
            scrollDown()
            device.pressBack()
            device.pressBack()
            device.pressBack()
            scrollDown()
        }
        device.pressBack()

        await(By.desc("Map")).click()
        Thread.sleep(3_000)
        device.pressBack()
        device.pressBack()

        await(By.desc("Search"))
        scrollTo(By.text("All lines")).click()
        await(By.text(Pattern.compile("\\d+[A-Z]*"))).click()
        Thread.sleep(3_000)
        device.pressBack()
        device.pressBack()
        scrollDown()
    }

    private fun MacrobenchmarkScope.await(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), WAIT_MS) ?: error("not found: $selector")

    private fun MacrobenchmarkScope.scrollTo(selector: BySelector): UiObject2 {
        val h = device.displayHeight
        repeat(15) {
            val found = device.findObject(selector)
            if (found != null && found.visibleCenter.y in h / 4..h * 3 / 4) return found
            device.swipe(device.displayWidth / 2, h * 2 / 3, device.displayWidth / 2, h / 3, 60)
            // Wait for the fling to settle.
            Thread.sleep(800)
        }
        error("not reached: $selector")
    }

    private fun MacrobenchmarkScope.scrollDown() {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 20)
        device.waitForIdle()
    }
}
