package pt.tiago.zerodroid.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import pt.tiago.zerodroid.R

private fun inter(weight: Int) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Inter (UI), bundled as one variable font with real weights per instance. */
val ZcFontFamily = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** JetBrains Mono for commands, code and ids. */
val ZcMonoFamily = FontFamily(
    Font(R.font.jetbrainsmono_regular, weight = FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium, weight = FontWeight.Medium),
    Font(R.font.jetbrainsmono_bold, weight = FontWeight.Bold),
)

object ZcType {
    val display = TextStyle(fontFamily = ZcFontFamily, fontWeight = FontWeight(700), fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em, color = ZcTokens.text)
    val title = TextStyle(fontFamily = ZcFontFamily, fontWeight = FontWeight(650), fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em, color = ZcTokens.text)
    val heading = TextStyle(fontFamily = ZcFontFamily, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 22.sp, color = ZcTokens.text)
    val body = TextStyle(fontFamily = ZcFontFamily, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 22.sp, color = ZcTokens.text)
    val bodyStrong = body.copy(fontWeight = FontWeight(600))
    val small = TextStyle(fontFamily = ZcFontFamily, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 18.sp, color = ZcTokens.muted)
    val caption = TextStyle(fontFamily = ZcFontFamily, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp, color = ZcTokens.muted)
    val label = TextStyle(fontFamily = ZcFontFamily, fontWeight = FontWeight(600), fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em, color = ZcTokens.faint)
    val metric = TextStyle(fontFamily = ZcFontFamily, fontWeight = FontWeight(700), fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em, color = ZcTokens.text)
    val button = TextStyle(fontFamily = ZcFontFamily, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 18.sp)
    val mono = TextStyle(fontFamily = ZcMonoFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp, color = ZcTokens.text)
    val monoSmall = mono.copy(fontSize = 12.sp, lineHeight = 16.sp)
}
