package com.keystone.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keystone.designsystem.theme.KeystoneTheme

enum class StatusTone { Neutral, Success, Warning, Error }

/** Small status label: a coloured dot plus text, so meaning never relies on colour alone. */
@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val dot: Color = when (tone) {
        StatusTone.Neutral -> MaterialTheme.colorScheme.outline
        StatusTone.Success -> KeystoneTheme.status.success
        StatusTone.Warning -> KeystoneTheme.status.warning
        StatusTone.Error -> MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Preview
@Composable
private fun StatusPillPreview() {
    KeystoneTheme { StatusPill("Backend connected", StatusTone.Success) }
}
