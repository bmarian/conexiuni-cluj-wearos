package online.bmarian.bus

import online.bmarian.bus.ui.theme.LineLooks
import org.junit.Assert.assertEquals
import org.junit.Test

class LineColorsTest {
    private val colorfuls = mapOf("25" to "#462EE0", "35" to "#DA771B")

    @Test
    fun favoritesTakeTheirOwn() {
        val looks = LineLooks(colorful = false, favorites = setOf("25"), colorfuls = colorfuls)
        assertEquals("#462EE0", looks.hex("25", "#1D71B8"))
        assertEquals("#1D71B8", looks.hex("35", "#1D71B8"))
        assertEquals("#DA771B", looks.hex("35", "#1D71B8", apart = true))
        assertEquals("#1D71B8", looks.hex("99", "#1D71B8", apart = true))
    }
}
