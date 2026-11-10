package com.slidedify.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.ThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.slidedify.core.designsystem.SlidedifyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    companion object {
        /** Extra telling the app what to open, e.g. from a notification. */
        const val EXTRA_OPEN = "open"
        const val OPEN_NEW_LIKES = "new_likes"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val lastCrash = CrashReport.read(this)
        val openNewLikes = intent?.getStringExtra(EXTRA_OPEN) == OPEN_NEW_LIKES
        setContent {
            val themeFlow = remember { settingsRepository.settings.map { it.theme }.distinctUntilChanged() }
            val mode = themeFlow.collectAsState(initial = ThemeMode.Dark).value
            val dark = when (mode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> isSystemInDarkTheme()
            }
            // Status and navigation bar icons follow the app's theme, not only the system's.
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            SlidedifyTheme(darkTheme = dark) {
                SlidedifyNavHost(openNewLikes = openNewLikes)
                if (lastCrash != null) LastCrashDialog(lastCrash)
            }
        }
    }
}

/** New GitHub issue with the report filled in (trimmed: URLs have a length limit). */
private fun issueUrl(report: String): String {
    val title = "Error: " + report.lineSequence().drop(2).firstOrNull().orEmpty().take(80)
    val body = "```\n${report.take(3_500)}\n```"
    return "https://github.com/luismaSE/slidedify/issues/new?title=${Uri.encode(title)}&body=${Uri.encode(body)}"
}

/** Shown once after a crash so the error can be copied and sent. */
@Composable
private fun LastCrashDialog(report: String) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    val copied = stringResource(R.string.crash_copied)
    val sendWith = stringResource(R.string.crash_send_with)
    val close = {
        CrashReport.clear(context)
        visible = false
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Text(
                report,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Error de Compass", report))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.crash_copy)) }
                TextButton(onClick = {
                    // Any app the user has: WhatsApp, mail, notes...
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Error de Compass")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, sendWith)) }
                    close()
                }) { Text(stringResource(R.string.crash_send)) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl(report)))) }
                    close()
                }) { Text(stringResource(R.string.crash_github)) }
                TextButton(onClick = close) { Text(stringResource(R.string.crash_close)) }
            }
        },
    )
}
