package com.cytube.mobile.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cytube.mobile.data.AppAlert

/** An update or news alert above the home page's search bar (see UpdateAlerts): one slim line, a
 *  button to open its link if it has one, and ✕ to put it away for good. */
@Composable
internal fun AlertBanner(alert: AppAlert, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = on, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (alert.update) Icons.Default.SystemUpdate else Icons.Default.Campaign,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                alert.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp)
            )
            if (alert.link != null) {
                TextButton(onClick = onOpen) { Text(if (alert.update) "Get it" else "Open", color = on) }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
        }
    }
}
