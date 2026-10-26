package com.risaina.app.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
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
    /** Where reports of abuse and problems go (the contact on the privacy policy and the Play listing). */
    const val CONTACT_EMAIL = "rk.remo12345@gmail.com"
    const val OPEN_METEO = "https://open-meteo.com"
    const val CC_BY_4 = "https://creativecommons.org/licenses/by/4.0/"
}

/**
 * "Report a problem or abuse": an email to the developer, with the app version filled in. Google Play
 * asks for an in-app way to report. Without an email app, the contact section of the privacy policy.
 */
fun reportProblem(context: Context) {
    val subject = Uri.encode("Risaina report")
    val body = Uri.encode("What happened, and when:\n\n\n(App version ${BuildConfig.VERSION_NAME})")
    val email = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${LegalLinks.CONTACT_EMAIL}?subject=$subject&body=$body"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(email) }.onFailure {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LegalLinks.contact)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
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
