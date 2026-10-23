package online.bmarian.bus

import online.bmarian.bus.presentation.theme.LineLooks
import online.bmarian.bus.presentation.theme.hashedLineColor
import org.junit.Assert.assertEquals
import org.junit.Test

class LineColorsTest {
    @Test
    fun hashMatchesTheWebsite() {
        // Same table as utils/__tests__/lineColor.spec.ts.
        val expected = mapOf(
            "25" to "#462EE0", "35" to "#DA771B", "24B" to "#A119D7", "M26N" to "#32C322", "8L" to "#2A52CB",
            "101" to "#DD1DBA", " 5n " to "#2552E4", "Ș1" to "#1FC16B", "RoHu" to "#DE3581", "N104" to "#70C328",
        )
        for ((name, color) in expected) assertEquals(name, color, hashedLineColor(name))
    }

    @Test
    fun favoritesTakeTheirOwn() {
        val looks = LineLooks(colorful = false, favorites = setOf("25"))
        assertEquals(hashedLineColor("25"), looks.hex("25", "#1D71B8"))
        assertEquals("#1D71B8", looks.hex("35", "#1D71B8"))
        assertEquals(hashedLineColor("35"), looks.hex("35", "#1D71B8", apart = true))
    }
}
