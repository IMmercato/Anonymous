package com.example.anonymous.utils

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.core.net.toUri

// Link types
private enum class LinkType(val tag: String) {
    URL("URL"),
    EMAIL("EMAIL"),
    PHONE("PHONE")
}

private data class LinkMatch(
    val value: String,
    val range: IntRange,
    val type: LinkType
)

// Patterns
private val URL_PATTERN = Regex(
    buildString {
        append("""(https?://[\w\-]+(\.[\w\-]+)+([\w\-.,@?^=%&:/~+#]*[\w\-@?^=%&/~+#])?)""")
        append("""|((www\.[\w\-]+(\.[\w\-]+)+([\w\-.,@?^=%&:/~+#]*[\w\-@?^=%&/~+#])?))""")
    }
)

private val EMAIL_PATTERN = Regex(
    """[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""
)

private val PHONE_PATTERN = Regex(
    """\+?[\d\s\-().]{7,15}\d"""
)

object Link {
    fun open(context: Context, url: String) {
        val uri = when {
            url.startsWith("http://") || url.startsWith("https://") -> url.toUri()
            else -> "https://$url".toUri()
        }
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(context, uri)
        } catch(e: ActivityNotFoundException) {
            try {

            } catch (e2: ActivityNotFoundException) {

            }
        }
    }

    fun sendEmail(context: Context, email: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO).apply { data = "mailto:$email".toUri() }
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    fun dial(context: Context, phone: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL).apply { data = "tel:$phone".toUri() })
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No phone app found", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToClipboard(context: Context, text: String, label: String = "Link") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun LinkifyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    email: Boolean = true,
    phone: Boolean = false,
    onLinkClick: ((String) -> Unit)? = null
) {
    val context = LocalContext.current

    val matches = remember(text, email, phone) {
        buildList {
            URL_PATTERN.findAll(text).forEach { add(LinkMatch(it.value, it.range, LinkType.URL)) }
            if (email) EMAIL_PATTERN.findAll(text).forEach { add(LinkMatch(it.value, it.range, LinkType.EMAIL)) }
            if (phone) PHONE_PATTERN.findAll(text).forEach { add(LinkMatch(it.value, it.range, LinkType.PHONE)) }
        }.sortedBy { it.range.first }
            .fold(emptyList<LinkMatch>()) { acc, match ->
                if (acc.isNotEmpty() && match.range.first <= acc.last().range.last) acc
                else acc + match
            }
    }

    val annotatedString = remember(text, matches, linkColor) {
        buildAnnotatedString {
            var cursor = 0
            matches.forEach { match ->
                append(text.substring(cursor, match.range.first))
                pushStringAnnotation(tag = match.type.tag, annotation = match.value)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(match.value)
                }
                pop()
                cursor = match.range.last + 1
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }

    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }

    BasicText(
        text = annotatedString,
        modifier = modifier.pointerInput(annotatedString) {
            detectTapGestures(
                onTap = { offset ->
                    layoutResult.value?.let { layout ->
                        val position = layout.getOffsetForPosition(offset)
                        LinkType.entries.forEach { type ->
                            annotatedString
                                .getStringAnnotations(tag = type.tag, start = position, end = position)
                                .firstOrNull()?.let { annotation ->
                                    if (onLinkClick != null) {
                                        onLinkClick(annotation.item)
                                    } else {
                                        when (type) {
                                            LinkType.URL -> Link.open(context, annotation.item)
                                            LinkType.EMAIL -> Link.sendEmail(context, annotation.item)
                                            LinkType.PHONE -> Link.dial(context, annotation.item)
                                        }
                                    }
                                }
                        }
                    }
                },
                onLongPress = { offset ->
                    layoutResult.value?.let { layout ->
                        val position = layout.getOffsetForPosition(offset)
                        LinkType.entries.forEach { type ->
                            annotatedString
                                .getStringAnnotations(tag = type.tag, start = position, end = position)
                                .firstOrNull()?.let { annotation ->
                                    Link.copyToClipboard(context, annotation.item)
                                }
                        }
                    }
                }
            )
        },
        style = style,
        onTextLayout = { layoutResult.value = it }
    )
}