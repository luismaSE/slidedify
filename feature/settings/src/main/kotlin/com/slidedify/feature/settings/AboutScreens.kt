package com.slidedify.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.slidedify.core.designsystem.AppIcons

/** A library the app ships with, and its license. */
data class OpenSourceLibrary(val name: String, val author: String, val license: String, val url: String)

/** Third-party code inside the app (keep in sync with gradle/libs.versions.toml). */
val OpenSourceLibraries = listOf(
    OpenSourceLibrary("Kotlin, Coroutines, Serialization", "JetBrains", "Apache 2.0", "https://github.com/Kotlin"),
    OpenSourceLibrary("Jetpack Compose y Material 3", "Google (AndroidX)", "Apache 2.0", "https://developer.android.com/jetpack/androidx"),
    OpenSourceLibrary("AndroidX Activity, Lifecycle, Navigation", "Google (AndroidX)", "Apache 2.0", "https://developer.android.com/jetpack/androidx"),
    OpenSourceLibrary("AndroidX Room, DataStore, WorkManager", "Google (AndroidX)", "Apache 2.0", "https://developer.android.com/jetpack/androidx"),
    OpenSourceLibrary("AndroidX Palette, Browser, Core", "Google (AndroidX)", "Apache 2.0", "https://developer.android.com/jetpack/androidx"),
    OpenSourceLibrary("Dagger Hilt", "Google", "Apache 2.0", "https://github.com/google/dagger"),
    OpenSourceLibrary("OkHttp", "Square", "Apache 2.0", "https://github.com/square/okhttp"),
    OpenSourceLibrary("Coil", "Coil contributors", "Apache 2.0", "https://github.com/coil-kt/coil"),
    OpenSourceLibrary("Spotify Android Auth", "Spotify", "Apache 2.0", "https://github.com/spotify/android-auth"),
    OpenSourceLibrary("Google Play services Auth", "Google", "Android SDK License", "https://developer.android.com/studio/terms"),
)

private const val COMPASS_LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.settings_back)) } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** The privacy policy, bundled in the app (same text as docs/PRIVACY.md) so it reads offline. */
@Composable
fun PrivacyRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val paragraphs = remember {
        val text = context.resources.openRawResource(R.raw.privacy).bufferedReader().use { it.readText() }
        privacyParagraphs(text)
    }
    AboutScaffold(stringResource(R.string.about_privacy), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            items(paragraphs) { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

/** Blank lines separate paragraphs; "- " lines are bullets; other line breaks are just wrapping. */
internal fun privacyParagraphs(text: String): List<String> =
    text.trim().split(Regex("\\n\\s*\\n")).map { paragraph ->
        paragraph.lines()
            .joinToString("\n")
            .replace(Regex("\\n- "), "\n• ")
            .replace(Regex("^- "), "• ")
            .replace(Regex("\\n(?!• )"), " ")
    }

@Composable
fun LicensesRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val open: (String) -> Unit = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    AboutScaffold(stringResource(R.string.about_licenses), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable(role = Role.Button) { open(COMPASS_LICENSE_URL) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Compass", style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.about_compass_license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.about_third_party),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(OpenSourceLibraries) { library ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { open(library.url) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(library.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${library.author} · ${library.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}
