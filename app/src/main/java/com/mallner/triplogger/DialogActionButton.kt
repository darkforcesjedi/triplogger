package com.mallner.triplogger

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Dialog action button with guaranteed contrast on dialog surfaces: the
 * dynamic color schemes (One UI) hand out a mid-tone blue `primary` that
 * gets hard to read on the neutral-gray dialog container, so dialog actions
 * use `onSurface` instead.
 */
@Composable
fun DialogActionButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    ) {
        Text(label)
    }
}
