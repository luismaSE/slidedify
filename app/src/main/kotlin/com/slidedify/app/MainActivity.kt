package com.slidedify.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.slidedify.core.designsystem.SlidedifyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val lastCrash = CrashReport.read(this)
        setContent {
            SlidedifyTheme {
                SlidedifyNavHost()
                if (lastCrash != null) LastCrashDialog(lastCrash)
            }
        }
    }
}

/** Shown once after a crash so the error can be copied and sent. */
@Composable
private fun LastCrashDialog(report: String) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    val close = {
        CrashReport.clear(context)
        visible = false
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Compass se cerró la última vez") },
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
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Error de Compass", report))
                Toast.makeText(context, "Error copiado", Toast.LENGTH_SHORT).show()
                close()
            }) { Text("Copiar error") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cerrar") } },
    )
}
