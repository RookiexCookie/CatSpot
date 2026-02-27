package com.sidespot.ui

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
import androidx.compose.ui.unit.dp

fun Modifier.focusHighlight(
    color: Color = Color.Unspecified,
    shape: Shape = RectangleShape,
): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    val borderColor = if (hasFocus) resolvedColor else Color.Transparent

    this
        .onFocusChanged { hasFocus = it.hasFocus }
        .focusable()
        .border(2.dp, borderColor, shape)
        .padding(8.dp)
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
