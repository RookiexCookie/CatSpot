package com.sidespot.ui

import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val DPAD_BACK_CODES = setOf(
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

fun Modifier.focusHighlight(
    color: Color = Color.Unspecified,
    shape: Shape = RectangleShape,
    onEnterKey: (() -> Unit)? = null,
    padding: Dp = 10.dp,
): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    val borderColor = if (hasFocus) resolvedColor else Color.Transparent

    this
        .onFocusChanged { hasFocus = it.hasFocus }
        .then(
            if (onEnterKey != null) {
                Modifier.onPreviewKeyEvent { event ->
                    if (hasFocus && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        val kc = event.nativeKeyEvent.keyCode
                        if (kc == KeyEvent.KEYCODE_ENTER ||
                            (kc == KeyEvent.KEYCODE_DPAD_CENTER && event.nativeKeyEvent.isLongPress)
                        ) {
                            onEnterKey()
                            true
                        } else false
                    } else false
                }
            } else Modifier
        )
        .focusable()
        .border(2.dp, borderColor, shape)
        .padding(padding)
}

/** Darkens a button when focused — use on filled/primary-colored buttons. */
fun Modifier.focusDarken(): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }

    this
        .onFocusChanged { hasFocus = it.hasFocus }
        .drawWithContent {
            drawContent()
            if (hasFocus) {
                drawRect(Color.Black.copy(alpha = 0.3f))
            }
        }
}

/** Intercept DPAD_LEFT/RIGHT inside bottom sheet popups and dismiss. */
fun Modifier.dismissOnDpad(onDismiss: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
            event.nativeKeyEvent.keyCode in DPAD_BACK_CODES
        ) {
            onDismiss()
            true
        } else false
    }
