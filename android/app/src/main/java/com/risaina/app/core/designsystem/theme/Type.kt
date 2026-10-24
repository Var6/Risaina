package com.risaina.app.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.risaina.app.R

/**
 * Editorial type system.
 *
 * Fraunces (variable serif, OFL) carries emotional headlines; its optical size axis is driven so
 * large sizes get the high-contrast "display" cut and small sizes stay sturdy. Inter (variable
 * sans, OFL) carries everything readable and interactive. Both are bundled; nothing is fetched
 * at runtime. Font files: res/font, licences: android/licenses.
 */
private fun fraunces(weight: Int, opsz: Float, soft: Float = 50f, italic: Boolean = false) = Font(
    resId = if (italic) R.font.fraunces_italic_variable else R.font.fraunces_variable,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opsz),
        FontVariation.Setting("SOFT", soft),
        FontVariation.Setting("WONK", 0f),
    ),
)

private fun inter(weight: Int, opsz: Float = 14f, italic: Boolean = false) = Font(
    resId = if (italic) R.font.inter_italic_variable else R.font.inter_variable,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opsz),
    ),
)

val FrauncesDisplay = FontFamily(
    fraunces(400, 144f), fraunces(500, 144f), fraunces(600, 144f),
    fraunces(400, 144f, italic = true), fraunces(500, 144f, italic = true),
)
val FrauncesText = FontFamily(
    fraunces(400, 24f), fraunces(500, 24f), fraunces(600, 24f),
    fraunces(400, 24f, italic = true),
)
val InterFamily = FontFamily(
    inter(400), inter(500), inter(600), inter(700),
    inter(400, italic = true),
)
val InterDisplay = FontFamily(inter(500, 28f), inter(600, 28f), inter(700, 28f))

private val tightHeading = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

/** Material typography roles mapped onto the editorial scale. */
val RisainaTypography = Typography(
    // Hero: partner greeting, welcome
    displayLarge = TextStyle(fontFamily = FrauncesDisplay, fontWeight = FontWeight.Normal, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.02).em, lineHeightStyle = tightHeading),
    displayMedium = TextStyle(fontFamily = FrauncesDisplay, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.015).em, lineHeightStyle = tightHeading),
    displaySmall = TextStyle(fontFamily = FrauncesDisplay, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em, lineHeightStyle = tightHeading),
    // Section titles
    headlineLarge = TextStyle(fontFamily = FrauncesText, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = FrauncesText, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontFamily = FrauncesText, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 24.sp),
    // Titles for cards and rows
    titleLarge = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    // Body
    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    // Buttons, chips, captions, timestamps
    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.01.em),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.02.em),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.04.em),
)

/** Extra roles the Material set does not name. */
object RisainaTextStyles {
    val heroItalic = TextStyle(fontFamily = FrauncesDisplay, fontWeight = FontWeight.Normal, fontStyle = FontStyle.Italic, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.02).em)
    val noteSerif = TextStyle(fontFamily = FrauncesText, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 27.sp)
    val widgetTitle = TextStyle(fontFamily = FrauncesText, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp)
    val timestamp = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.04.em)
    val overline = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em)
    val emojiLarge = TextStyle(fontSize = 44.sp, lineHeight = 52.sp)
}
