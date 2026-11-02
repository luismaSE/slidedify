package com.slidedify.feature.connections

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.slidedify.core.designsystem.AppIcons
import com.slidedify.core.designsystem.OverlineStyle
import com.slidedify.core.designsystem.ProviderBadge
import com.slidedify.core.designsystem.Tag
import com.slidedify.core.domain.outbox.SyncStatus
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.ProviderCapabilities
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.model.ProviderId
import com.slidedify.core.platform.ProviderSignIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Connection(
    val id: ProviderId,
    val name: String,
    val auth: AuthState,
    val isActive: Boolean,
    /** The app can start this service's login (false for the demo). */
    val canSignIn: Boolean,
    val capabilities: ProviderCapabilities,
)

data class ConnectionsUiState(
    val connections: List<Connection> = emptyList(),
    val pendingSync: Int = 0,
    /** Services this build knows about but are not set up (missing API keys). */
    val unavailable: List<String> = emptyList(),
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val signIns: Set<@JvmSuppressWildcards ProviderSignIn>,
    syncStatus: SyncStatus,
) : ViewModel() {
    private val authStates = registry.all.map { it.auth.state }
    private val allAuth = if (authStates.isEmpty()) flowOf(emptyList()) else combine(authStates) { it.toList() }

    val state: StateFlow<ConnectionsUiState> = combine(allAuth, registry.active, syncStatus.pendingCount) { auths, active, pending ->
        val ids = registry.all.map { it.id.value }.toSet()
        ConnectionsUiState(
            connections = registry.all.zip(auths) { provider, auth ->
                Connection(
                    id = provider.id,
                    name = provider.displayName,
                    auth = auth,
                    isActive = provider.id == active.id,
                    canSignIn = signIns.any { it.providerId == provider.id },
                    capabilities = provider.capabilities,
                )
            }.sortedWith(compareByDescending<Connection> { it.isActive }.thenBy { it.id.value == "demo" }),
            pendingSync = pending,
            unavailable = KNOWN_SERVICES.filterKeys { it !in ids }.values.toList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())

    fun connect(context: Context, id: ProviderId) {
        registry.select(id)
        signIns.firstOrNull { it.providerId == id }?.start(context)
    }

    fun disconnect(id: ProviderId) {
        viewModelScope.launch { registry.find(id)?.auth?.signOut() }
    }

    fun use(id: ProviderId) = registry.select(id)

    private companion object {
        val KNOWN_SERVICES = mapOf("spotify" to "Spotify", "tidal" to "TIDAL", "youtube" to "YouTube Music")
    }
}

@Composable
fun ConnectionsRoute(viewModel: ConnectionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ConnectionsScreen(
        state = state,
        onConnect = { viewModel.connect(context, it) },
        onDisconnect = viewModel::disconnect,
        onUse = viewModel::use,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    onConnect: (ProviderId) -> Unit,
    onDisconnect: (ProviderId) -> Unit,
    onUse: (ProviderId) -> Unit,
) {
    var confirmDisconnect by remember { mutableStateOf<Connection?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Conexiones") }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Conectá tus servicios de música. El activo es el que usás para ordenar; podés cambiarlo cuando quieras.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                )
            }
            items(state.connections, key = { it.id.value }) { connection ->
                ConnectionCard(
                    connection = connection,
                    onConnect = { onConnect(connection.id) },
                    onDisconnect = { confirmDisconnect = connection },
                    onUse = { onUse(connection.id) },
                )
            }
            if (state.unavailable.isNotEmpty()) {
                item { Text("PRÓXIMAMENTE", style = OverlineStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
                items(state.unavailable, key = { "soon-$it" }) { name -> UnavailableRow(name) }
            }
            item { SyncCard(state.pendingSync) }
        }
    }

    confirmDisconnect?.let { connection ->
        AlertDialog(
            onDismissRequest = { confirmDisconnect = null },
            title = { Text("¿Desconectar ${connection.name}?") },
            text = { Text("Tu progreso queda guardado en este teléfono. Para volver a usarlo vas a tener que iniciar sesión de nuevo.") },
            confirmButton = {
                TextButton(onClick = {
                    onDisconnect(connection.id)
                    confirmDisconnect = null
                }) { Text("Desconectar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = null }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionCard(connection: Connection, onConnect: () -> Unit, onDisconnect: () -> Unit, onUse: () -> Unit) {
    val signedIn = connection.auth is AuthState.SignedIn
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (connection.isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(connection.id.value, connection.name, size = 44.dp)
                Column(
                    Modifier
                        .padding(start = 14.dp)
                        .weight(1f),
                ) {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium)
                    StatusLine(connection.auth)
                }
                if (connection.isActive) Tag("Activo")
            }

            FlowRow(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                connection.capabilities.labels().forEach { label -> Capability(label) }
            }

            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    signedIn && !connection.isActive -> Button(onClick = onUse) { Text("Usar este") }
                    !signedIn && connection.canSignIn -> Button(onClick = onConnect) { Text("Conectar") }
                    else -> Unit
                }
                if (signedIn && connection.canSignIn) {
                    OutlinedButton(onClick = onDisconnect) {
                        Icon(AppIcons.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Desconectar")
                    }
                }
                if (!signedIn && !connection.canSignIn) {
                    Text(
                        "No se puede conectar desde esta versión.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(auth: AuthState) {
    val (color, text) = when (auth) {
        is AuthState.SignedIn -> MaterialTheme.colorScheme.primary to "Conectado como ${auth.userName}"
        is AuthState.Error -> MaterialTheme.colorScheme.error to auth.message
        AuthState.SignedOut -> MaterialTheme.colorScheme.onSurfaceVariant to "Sin conectar"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun Capability(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun ProviderCapabilities.labels(): List<String> = buildList {
    if (canLike) add("Me gusta")
    if (canCreatePlaylists) add("Crear playlists")
    if (canSearch) add("Recibe transferencias")
    add(if (canPlayFullTracks) "Reproducción completa" else "Vistas previas")
}

@Composable
private fun UnavailableRow(name: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderBadge(name.lowercase().substringBefore(' '), name, size = 32.dp)
            Column(
                Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
            ) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Todavía no está activado en esta versión",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SyncCard(pending: Int) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (pending == 0) AppIcons.Check else AppIcons.Sync,
                contentDescription = null,
                tint = if (pending == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    if (pending == 0) "Todo sincronizado" else "$pending cambios por sincronizar",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Los cambios se guardan en el teléfono y se envían solos cuando hay conexión.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
