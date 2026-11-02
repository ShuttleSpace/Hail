package com.aistra.hail.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.unit.dp
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HUI
import java.text.SimpleDateFormat

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val installTime = remember { HPackages.getUnhiddenPackageInfoOrNull(app.packageName)?.firstInstallTime ?: System.currentTimeMillis() }
    var openLicenseDialog by remember { mutableStateOf(false) }
    var donateDialog by remember { mutableStateOf(false) }
    var wechatDialog by remember { mutableStateOf(false) }

    if (openLicenseDialog) LicenseDialog { openLicenseDialog = false }
    if (donateDialog) DonateDialog(
        onDismiss = { donateDialog = false },
        onWechat = { donateDialog = false; wechatDialog = true },
    )
    if (wechatDialog) WechatQrDialog { wechatDialog = false }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(dimensionResource(R.dimen.padding_medium)))
        Card(
            onClick = { HUI.openLink(HailData.URL_WHY_FREE_SOFTWARE) },
            modifier = Modifier.height(dimensionResource(R.dimen.header_height))
                .padding(horizontal = dimensionResource(R.dimen.padding_medium)),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_extra_small), Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).background(Color.White, CircleShape),
                    contentScale = ContentScale.None,
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.app_slogan), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.padding_medium)))
        OutlinedCard(modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.padding_medium))) {
            ClickableItem(Icons.Outlined.Update, R.string.label_version, HailData.VERSION) { HUI.openLink(HailData.URL_RELEASES) }
            ClickableItem(Icons.Outlined.InstallMobile, R.string.label_time, SimpleDateFormat.getDateInstance().format(installTime)) { HUI.showToast("\uD83E\uDD76\uD83D\uDCA8\uD83D\uDC09") }
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.padding_medium)))
        OutlinedCard(modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.padding_medium))) {
            ClickableItem(Icons.AutoMirrored.Filled.Send, R.string.action_telegram) { HUI.openLink(HailData.URL_TELEGRAM) }
            ClickableItem(Icons.Outlined.Group, R.string.action_qq) { HUI.openLink(HailData.URL_QQ) }
            ClickableItem(Icons.Outlined.LocalMall, R.string.action_fdroid) { HUI.openLink(HailData.URL_FDROID) }
            ClickableItem(Icons.Outlined.Coffee, R.string.action_donate) { donateDialog = true }
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.padding_medium)))
        OutlinedCard(modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.padding_medium))) {
            ClickableItem(Icons.Outlined.Code, R.string.action_github) { HUI.openLink(HailData.URL_GITHUB) }
            ClickableItem(Icons.Outlined.Translate, R.string.action_translate) { HUI.openLink(HailData.URL_TRANSLATE) }
            ClickableItem(Icons.Outlined.Description, R.string.action_licenses) { openLicenseDialog = true }
        }
        Spacer(Modifier.height(dimensionResource(R.dimen.padding_medium)))
    }
}

@Composable
private fun ClickableItem(
    icon: ImageVector, title: Int, desc: String? = null, onClick: () -> Unit,
) = Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(
        imageVector = icon, contentDescription = null,
        modifier = Modifier.padding(
            horizontal = dimensionResource(R.dimen.padding_medium),
            vertical = dimensionResource(if (desc == null) R.dimen.padding_medium else R.dimen.padding_large),
        ),
    )
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        if (desc != null) Text(desc, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_licenses)) },
        text = {
            SelectionContainer {
                Text(
                    text = remember {
                        buildAnnotatedString {
                            val lines = context.resources.openRawResource(R.raw.licenses).bufferedReader().readLines()
                            lines.forEach {
                                if (it.isNotBlank()) withLink(
                                    LinkAnnotation.Url(it.substringAfter(": "), TextLinkStyles(style = SpanStyle(color = Color(0xFF32628D))))
                                ) { append(it.substringBefore(": ")) }
                                if (it != lines.last()) append("\n\n")
                            }
                        }
                    },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}

@Composable
private fun DonateDialog(onDismiss: () -> Unit, onWechat: () -> Unit) {
    val context = LocalContext.current
    val entries = context.resources.getStringArray(R.array.donate_payment_entries)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_donate)) },
        text = {
            Column {
                entries.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            when (index) {
                                0 -> if (!HUI.openLink(HailData.URL_ALIPAY_API)) HUI.openLink(HailData.URL_ALIPAY)
                                1 -> onWechat()
                                2 -> HUI.openLink(HailData.URL_KOFI)
                                3 -> HUI.openLink(HailData.URL_LIBERAPAY)
                                4 -> HUI.openLink(HailData.URL_PAYPAL)
                            }
                            if (index != 1) onDismiss()
                        }.padding(vertical = 12.dp),
                    ) { Text(entry) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun WechatQrDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_donate)) },
        text = {
            Image(
                painter = painterResource(R.mipmap.qr_wechat),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().padding(vertical = dimensionResource(R.dimen.padding_large)),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                context.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.let {
                    it.putExtra("LauncherUI.From.Scaner.Shortcut", true)
                    context.startActivity(it)
                } ?: HUI.showToast(R.string.app_not_installed)
                onDismiss()
            }) { Text(stringResource(R.string.donate_wechat_scan)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
