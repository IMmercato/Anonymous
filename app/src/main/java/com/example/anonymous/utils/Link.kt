package com.example.anonymous.utils

import android.content.Context
import android.content.Intent
import android.text.util.Linkify
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.core.net.toUri

object Link {
    fun open(context: Context, url: String) {
        try {
            val builder = CustomTabsIntent.Builder()
            builder.setShowTitle(true)
            builder.setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            val customTabsIntent = builder.build()
            val uri = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url".toUri()
            } else {
                url.toUri()
            }

            customTabsIntent.launchUrl(context, uri)
        } catch(e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            context.startActivity(intent)
        }
    }
}

@Composable
fun LinkifyText(
    text: String,
    textColor: Color = Color.Black,
    isCode: Boolean = false
) {
    val context = LocalContext.current

    // Regex
    val urlPattern = Regex("(https?://[\\w-]+(\\.[\\w-]+)+(/\\S*)?)")

    val annotatedString = buildAnnotatedString {
        var index = 0
        urlPattern.findAll(text).forEach { matchResult ->
            append(text.substring(index, matchResult.range.first))
            pushStringAnnotation(tag = "URL", annotation = matchResult.value)
            withStyle(style = SpanStyle(
                color = Color.Blue,
                textDecoration = TextDecoration.Underline
            )) {
                append(matchResult.value)
            }
            pop()
            index = matchResult.range.last + 1
        }
        if (index < text.length) {
            append(text.substring(index))
        }
    }

    ClickableText(
        text = annotatedString,
        style = MaterialTheme.typography.bodyLarge.copy(color = if (isCode) Color.White else textColor),
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    Link.open(context, annotation.item)
                }
        }
    )
}