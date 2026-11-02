package com.slidedify.feature.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slidedify.core.designsystem.AppIcons
import com.slidedify.core.designsystem.CompassMark
import com.slidedify.core.designsystem.CoverArt
import com.slidedify.core.designsystem.EmptyState
import com.slidedify.core.designsystem.OverlineStyle
import com.slidedify.core.designsystem.ProviderBadge
import com.slidedify.core.designsystem.SectionHeader
import com.slidedify.core.designsystem.Skeleton
import com.slidedify.core.designsystem.SlimProgress
import com.slidedify.core.designsystem.Tag
import com.slidedify.core.designsystem.accentFor
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.repository.key
import com.slidedify.core.model.ProviderId

/** Shows the search box once the list is long enough to need it. */
private const val SEARCH_THRESHOLD = 8

@Composable
fun HomeRoute(
    onOpenSource: (TrackSource, String) -> Unit,
    onOpenConnections: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    HomeScreen(
        state = state,
        onOpenSource = { onOpenSource(it.source, it.title) },
        onSelectProvider = viewModel::selectProvider,
        onSignIn = { viewModel.signIn(context) },
        onRetry = viewModel::retry,
        onOpenConnections = onOpenConnections,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenSource: (SourceCard) -> Unit,
    onSelectProvider: (ProviderId) -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onOpenConnections: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val signedIn = state.auth is AuthState.SignedIn
    val lastKey = state.continueCard?.source?.key

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "header") {
            HomeHeader(state, onSelectProvider, onOpenConnections)
        }

        if (!signedIn) {
            item(key = "welcome") {
                Welcome(state, onSignIn, onSelectProvider, onOpenConnections)
            }
            return@LazyColumn
        }

        item(key = "greeting") { Greeting(state.userName) }

        state.continueCard?.let { card ->
            item(key = "continue") { ContinueCard(card, onClick = { onOpenSource(card) }) }
        }

        if (state.recent.isNotEmpty()) {
            item(key = "recent-title") { SectionHeader("Recientes") }
            item(key = "recent") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.recent, key = { it.source.key }) { card -> RecentTile(card) { onOpenSource(card) } }
                }
            }
        }

        if (state.shortcuts.isNotEmpty()) {
            item(key = "shortcuts-title") { SectionHeader("Accesos rápidos") }
            item(key = "shortcuts") {
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.shortcuts.forEach { card ->
                        ShortcutTile(card, Modifier.weight(1f)) { onOpenSource(card) }
                    }
                }
            }
        }

        item(key = "playlists-title") {
            SectionHeader(if (state.playlists.isEmpty()) "Tus playlists" else "Tus playlists · ${state.playlists.size}")
        }
        state.error?.let { error ->
            item(key = "error") { ErrorRow(error, onRetry) }
        }
        if (state.isLoading) {
            items(5, key = { "skeleton-$it" }) { PlaylistSkeleton() }
        } else {
            if (state.playlists.size > SEARCH_THRESHOLD) {
                item(key = "search") { SearchField(query) { query = it } }
            }
            val visible = if (query.isBlank()) state.playlists else state.playlists.filter { it.title.contains(query.trim(), ignoreCase = true) }
            if (visible.isEmpty() && state.error == null) {
                item(key = "empty") {
                    EmptyState(
                        icon = if (query.isBlank()) AppIcons.Library else AppIcons.Search,
                        title = if (query.isBlank()) "Todavía no tenés playlists" else "Sin resultados",
                        message = if (query.isBlank()) {
                            "Creá una desde la pantalla de ordenar con el botón «Playlist»."
                        } else {
                            "No hay playlists que coincidan con «${query.trim()}»."
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(visible, key = { it.source.key }) { card ->
                PlaylistRow(card, isLast = card.source.key == lastKey) { onOpenSource(card) }
            }
        }
    }
}

@Composable
private fun HomeHeader(state: HomeUiState, onSelectProvider: (ProviderId) -> Unit, onOpenConnections: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompassMark(size = 30.dp)
        Text(
            "Compass",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f),
        )
        state.provider?.let { provider ->
            ProviderSwitcher(provider, state.providers, onSelectProvider, onOpenConnections)
        }
    }
}

/** Pill with the active service; tapping it lists the others plus "Administrar conexiones". */
@Composable
private fun ProviderSwitcher(
    active: ProviderChoice,
    all: List<ProviderChoice>,
    onSelect: (ProviderId) -> Unit,
    onOpenConnections: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.semantics { contentDescription = "Servicio: ${active.name}. Tocá para cambiar" },
        ) {
            Row(Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(active.id.value, active.name, size = 24.dp)
                Text(active.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            all.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.name) },
                    leadingIcon = { ProviderBadge(provider.id.value, provider.name, size = 24.dp) },
                    trailingIcon = {
                        if (provider.id == active.id) Icon(AppIcons.Check, contentDescription = "Activo", tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = {
                        open = false
                        onSelect(provider.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Administrar conexiones") },
                leadingIcon = { Icon(AppIcons.Link, contentDescription = null) },
                onClick = {
                    open = false
                    onOpenConnections()
                },
            )
        }
    }
}

@Composable
private fun Greeting(userName: String?) {
    val first = userName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() && it != "demo" }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            if (first != null) "Hola, $first" else "Hola",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            "¿Qué ordenamos hoy?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The last source sorted, big and first, so continuing is one tap. */
@Composable
private fun ContinueCard(card: SourceCard, onClick: () -> Unit) {
    val accent = accentFor(card.title)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier.background(
                Brush.horizontalGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent)),
            ),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    if (card.isDone) "TERMINASTE" else "SEGUÍ DONDE QUEDASTE",
                    style = OverlineStyle,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverArt(card.imageUrl, card.title, size = 76.dp, corner = 14.dp, icon = card.source.icon())
                    Column(
                        Modifier
                            .padding(start = 14.dp)
                            .weight(1f),
                    ) {
                        Text(card.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(card.progressLabel(), card.openedAt?.let(::relativeTime)).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                card.progress?.let { progress ->
                    SlimProgress(progress, Modifier.fillMaxWidth().padding(top = 16.dp))
                }
                Button(
                    onClick = onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(48.dp),
                ) {
                    Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (card.isDone) "Revisar de nuevo" else "Continuar")
                }
            }
        }
    }
}

@Composable
private fun RecentTile(card: SourceCard, onClick: () -> Unit) {
    Column(
        Modifier
            .width(116.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(bottom = 4.dp),
    ) {
        CoverArt(card.imageUrl, card.title, size = 116.dp, corner = 16.dp, icon = card.source.icon())
        Text(
            card.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        card.progress?.let { SlimProgress(it, Modifier.fillMaxWidth().padding(top = 6.dp)) }
            ?: Text(card.progressLabel() ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShortcutTile(card: SourceCard, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(null, card.title, size = 40.dp, corner = 10.dp, icon = card.source.icon())
            Column(Modifier.padding(start = 10.dp)) {
                Text(card.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    card.progressLabel() ?: "Empezar",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(card: SourceCard, isLast: Boolean, onClick: () -> Unit) {
    val container = if (isLast) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = if (isLast) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(card.imageUrl, card.title, size = 52.dp, corner = 10.dp)
            Column(
                Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isLast) Tag("Última", Modifier.padding(start = 8.dp))
                }
                Text(
                    listOfNotNull(card.total?.let { "$it canciones" }, card.progressLabel(short = true)).joinToString(" · ").ifEmpty { "Playlist" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
                card.progress?.takeIf { it > 0f && !card.isDone }?.let {
                    SlimProgress(it, Modifier.fillMaxWidth(0.6f).padding(top = 6.dp))
                }
            }
            if (card.isDone) {
                Icon(AppIcons.Check, contentDescription = "Terminada", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            } else {
                Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun PlaylistSkeleton() {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Skeleton(Modifier.size(52.dp), corner = 10.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Skeleton(Modifier.width(160.dp).height(14.dp))
            Skeleton(Modifier.padding(top = 8.dp).width(96.dp).height(10.dp))
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Buscar playlist") },
        leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Icon(
                    AppIcons.Close,
                    contentDescription = "Borrar búsqueda",
                    modifier = Modifier.clickable(role = Role.Button) { onChange("") },
                )
            }
        },
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedBorderColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Reintentar") }
        }
    }
}

/** First screen when not connected: what the app does and how to start. */
@Composable
private fun Welcome(
    state: HomeUiState,
    onSignIn: () -> Unit,
    onSelectProvider: (ProviderId) -> Unit,
    onOpenConnections: () -> Unit,
) {
    val providerName = state.provider?.name.orEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompassMark(size = 112.dp)
        Text(
            "Bienvenido a Compass",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            "Ordená tu música deslizando: cada canción va a la playlist que elijas con un gesto.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Step(1, "Conectá tu cuenta", "Spotify u otro servicio compatible.")
            Step(2, "Elegí qué ordenar", "Tus Me gusta o cualquier playlist.")
            Step(3, "Deslizá", "Hacia una playlist, o mantené apretado para elegir varias.")
        }

        (state.auth as? AuthState.Error)?.let { error ->
            Text(
                error.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 20.dp),
            )
        }

        if (state.canSignIn) {
            Button(
                onClick = onSignIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp)
                    .height(52.dp),
            ) { Text("Conectar con $providerName") }
        }
        OutlinedButton(
            onClick = onOpenConnections,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(52.dp),
        ) { Text("Ver servicios disponibles") }
        state.demo?.takeIf { it.id != state.provider?.id }?.let { demo ->
            TextButton(onClick = { onSelectProvider(demo.id) }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Probar sin cuenta (demo)")
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun TrackSource.icon() = when (this) {
    TrackSource.LikedSongs -> AppIcons.Heart
    TrackSource.Recommended -> AppIcons.Queue
    is TrackSource.FromPlaylist -> null
}

private fun SourceCard.progressLabel(short: Boolean = false): String? = when {
    isDone -> "Completa"
    total != null && sorted > 0 -> if (short) "${((progress ?: 0f) * 100).toInt()}% ordenado" else "$sorted de $total"
    sorted > 0 -> "$sorted revisadas"
    else -> if (short) null else "Sin empezar"
}

/** "hace 5 min", "ayer"… */
private fun relativeTime(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "recién"
        minutes < 60 -> "hace $minutes min"
        minutes < 24 * 60 -> "hace ${minutes / 60} h"
        minutes < 48 * 60 -> "ayer"
        else -> "hace ${minutes / (24 * 60)} días"
    }
}
