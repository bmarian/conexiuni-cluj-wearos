package com.example.conexiuniclujwearos.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

// The website's simplified layout colours (Tailwind slate and emerald on black).
object Palette {
    val Background = Color.Black
    val Text = Color(0xFFF1F5F9)
    val Title = Color(0xFFF8FAFC)
    val Sub = Color(0xFF94A3B8)
    val Label = Color(0xFF64748B)
    val Action = Color(0xFFCBD5E1)
    val Faint = Color(0xFF475569)
    val Accent = Color(0xFF34D399)
    val AccentStrong = Color(0xFF10B981)
    val AccentDeep = Color(0xFF047857)
    val AccentWash = Color(0x2610B981)
    val Surface = Color(0xFF1E293B)
    val SurfaceLine = Color(0xFF334155)
    val Deep = Color(0xFF0F172A)
    val Heart = Color(0xFFF43F5E)
    val FloatFill = Color(0xFF1E3A5F)
    val FloatLine = Color(0xFF1D4ED8)
    val FloatText = Color(0xFF93C5FD)
    val StopGray = Color(0xFF64748B)
}

fun routeColor(hex: String): Color =
    runCatching { Color(hex.toColorInt()) }.getOrDefault(Palette.StopGray)

@Composable
fun ConexiuniClujWearOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.Deep,
            onSurface = Palette.Text,
            onSurfaceVariant = Palette.Sub,
            background = Palette.Background,
            onBackground = Palette.Text,
        ),
        content = content,
    )
}
