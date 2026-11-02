package com.aistra.hail.ui.compose

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads an app icon as an [ImageBitmap] off the main thread. */
@Composable
fun rememberAppIcon(
    info: ApplicationInfo?, size: Dp, grayscale: Boolean
): ImageBitmap? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, info?.packageName, grayscale, sizePx) {
        value = if (info == null) null else withContext(Dispatchers.IO) {
            AppIconCache.loadIconBitmap(context, info, HPackages.myUserId, sizePx)
        }
    }
    return bitmap?.asImageBitmap()
}

private val grayscaleFilter by lazy {
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
}

@Composable
fun AppIcon(
    info: ApplicationInfo?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    grayscale: Boolean = false,
) {
    val icon = rememberAppIcon(info, size, grayscale)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            colorFilter = if (grayscale) grayscaleFilter else null,
            modifier = modifier.size(size)
        )
    } else {
        Box(modifier.size(size).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/**
 * Small snowflake shown in the top-right corner of an app icon to indicate the
 * app is frozen. Place inside a [Box] (icon container) with [Alignment.TopEnd].
 */
@Composable
fun FrozenBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.size(18.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.AcUnit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Letter shown in the large A-Z drag bubble. Rendered at window level (see
 * HailRoot) so it is centered on the screen instead of next to the index bar.
 */
val LocalIndexBubble = compositionLocalOf<MutableState<Char?>?> { null }

@Composable
fun IndexBubble(state: MutableState<Char?>, modifier: Modifier = Modifier) {
    val letter = state.value ?: return
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
        modifier = modifier.size(88.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = letter.toString(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.displaySmall,
            )
        }
    }
}

/**
 * Vertical A-Z index, shown beside the list (never on top of it). The letter of
 * the section currently at the top of the list is highlighted; while dragging, a
 * large letter bubble is shown centered in the window.
 */
@Composable
fun LetterIndex(
    letters: List<Char>,
    current: Char?,
    onSelect: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.size <= 1) return
    val bubble = LocalIndexBubble.current
    var active by remember { mutableStateOf<Char?>(null) }
    val verticalPadding = 8.dp
    val highlighted = active ?: current
    BoxWithConstraints(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        val itemHeight = 16.dp.coerceAtMost((maxHeight - verticalPadding * 2) / letters.size)
        val fontSize = if (itemHeight < 16.dp) (itemHeight.value * 0.62f).sp else 10.sp
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.width(24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(letters, itemHeight) {
                        val pad = verticalPadding.toPx()
                        val itemPx = itemHeight.toPx()
                        fun letterAt(y: Float): Char =
                            letters[((y - pad) / itemPx).toInt().coerceIn(0, letters.lastIndex)]

                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val first = letterAt(down.position.y)
                            active = first
                            bubble?.value = first
                            onSelect(first)
                            down.consume()
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    val letter = letterAt(change.position.y)
                                    if (letter != active) {
                                        active = letter
                                        bubble?.value = letter
                                        onSelect(letter)
                                    }
                                    change.consume()
                                } while (event.changes.any { it.pressed })
                            } finally {
                                active = null
                                bubble?.value = null
                            }
                        }
                    }
                    .padding(vertical = verticalPadding),
            ) {
                letters.forEach { letter ->
                    Box(Modifier.height(itemHeight), contentAlignment = Alignment.Center) {
                        Text(
                            text = letter.toString(),
                            fontSize = fontSize,
                            color = if (letter == highlighted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

sealed interface SheetEntry {
    data class Action(
        val icon: ImageVector? = null,
        val title: String,
        val checked: Boolean? = null,
        val radio: Boolean = false,
        val keepOpen: Boolean = false,
        val onClick: () -> Unit,
    ) : SheetEntry

    data class Header(val title: String) : SheetEntry

    data class Stepper(
        val icon: ImageVector? = null,
        val title: String,
        val value: Int,
        val range: IntRange,
        val onChange: (Int) -> Unit,
    ) : SheetEntry

    data object Divider : SheetEntry
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(entries: List<SheetEntry>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            entries.forEach { entry ->
                when (entry) {
                    is SheetEntry.Action -> {
                        val checked = entry.checked
                        ListItem(
                            headlineContent = { Text(entry.title) },
                            leadingContent = if (entry.icon != null) {
                                { Icon(entry.icon, null) }
                            } else null,
                            trailingContent = if (checked != null) {
                                {
                                    if (entry.radio) RadioButton(selected = checked, onClick = null)
                                    else if (checked) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            } else null,
                            modifier = Modifier.clickable { entry.onClick(); if (!entry.keepOpen) onDismiss() }
                        )
                    }

                    is SheetEntry.Header -> Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp),
                    )

                    is SheetEntry.Stepper -> Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        entry.icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.width(24.dp))
                        Text(entry.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(entry.value.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        TextButton(onClick = { entry.onChange((entry.value - 1).coerceIn(entry.range)) }, enabled = entry.value > entry.range.first) { Text("−") }
                        TextButton(onClick = { entry.onChange((entry.value + 1).coerceIn(entry.range)) }, enabled = entry.value < entry.range.last) { Text("+") }
                    }

                    SheetEntry.Divider -> HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListBottomSheet(
    title: String,
    entries: List<String>,
    current: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 4.dp),
            )
            entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSelected(entry); onDismiss() }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(entry, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    RadioButton(selected = entry == current, onClick = null)
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: CharSequence) {
    Text(
        text = title.toString(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * App label. A padlock marks whitelisted apps; the label is dimmed when the
 * app is frozen (a snowflake badge is shown on the icon instead, see
 * [FrozenBadge]) or when it is not found.
 */
@Composable
fun AppLabel(
    name: CharSequence,
    frozen: Boolean,
    whitelisted: Boolean,
    selected: Boolean,
    notFound: Boolean,
    fontSize: Int = 14,
) {
    val color = when {
        notFound -> MaterialTheme.colorScheme.error
        frozen -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = if (whitelisted) "🔒$name" else name.toString(),
        color = color,
        fontSize = fontSize.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
