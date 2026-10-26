package com.risaina.app.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import com.risaina.app.BuildConfig
import com.risaina.app.core.designsystem.theme.Risaina

/**
 * Public pages the server hosts next to the API. Google Play needs the privacy policy linked inside
 * the app as well as in the store listing, and a web page for deleting an account without the app.
 */
object LegalLinks {
    private val base = BuildConfig.API_BASE_URL.trimEnd('/')
    val privacyPolicy = "$base/privacy"
    val deleteAccount = "$base/delete-account"
    val terms = "$base/terms"
    val contact = "$base/privacy#contact"
    const val OPEN_METEO = "https://open-meteo.com"
    const val CC_BY_4 = "https://creativecommons.org/licenses/by/4.0/"
}

/** Shown where an account can be created: by email, or by Google on the welcome and sign-in screens. */
const val AGREE_TO_TERMS = "By continuing, you agree to the Terms and the Privacy policy."
val agreeToTermsLinks get() = listOf("Terms" to LegalLinks.terms, "Privacy policy" to LegalLinks.privacyPolicy)

/** [text] with each `phrase to url` pair in [links] underlined and opening in the browser. */
@Composable
fun LinkedText(
    text: String,
    links: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    color: Color = Risaina.colors.textMuted,
    textAlign: TextAlign? = null,
) {
    val uri = LocalUriHandler.current
    val annotated = remember(text, links, uri) {
        val style = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            append(text)
            links.forEach { (phrase, url) ->
                val start = text.indexOf(phrase).takeIf { it >= 0 } ?: return@forEach
                addLink(LinkAnnotation.Url(url, style) { runCatching { uri.openUri(url) } }, start, start + phrase.length)
            }
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodySmall, color = color, textAlign = textAlign, modifier = modifier)
}
