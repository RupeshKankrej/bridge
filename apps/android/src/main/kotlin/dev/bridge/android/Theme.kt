package dev.bridge.android

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Material You: on Android 12+ (OxygenOS included) the colours come from the wallpaper like the system's own apps;
 * Android 10/11 get a fixed blue scheme. Follows the system dark mode.
 */
@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFA8C7FA))
        else -> lightColorScheme(primary = Color(0xFF0B57D0))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** A rounded group of rows, like the system Settings app's sections. */
@Composable
fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let {
            Text(it, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
    }
}

/** One settings row: icon, title, optional summary, and a trailing switch or chevron. */
@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val click = onClick ?: onCheckedChange?.let { { it(!(checked ?: false)) } }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(painterResource(it), null, tint = MaterialTheme.colorScheme.primary) } },
        trailingContent = when {
            trailing != null -> trailing

            checked != null -> {
                { Switch(checked = checked, onCheckedChange = onCheckedChange) }
            }

            onClick != null -> {
                { Icon(painterResource(R.drawable.ic_chevron_right), null) }
            }

            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (click != null) Modifier.clickable(onClick = click) else Modifier,
    )
}

/** Screen title row with an optional back arrow. */
@Composable
fun ScreenTitle(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (onBack != null) {
            androidx.compose.material3.IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }
            Spacer(Modifier.size(8.dp))
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        action?.invoke()
    }
}
