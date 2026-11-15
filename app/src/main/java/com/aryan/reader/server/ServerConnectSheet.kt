package com.aryan.reader.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aryan.reader.R
import kotlinx.coroutines.launch

private enum class ServerStep { LIST, PICK, FORM }

@Composable
private fun providerLabel(provider: ServerProvider): String = stringResource(
    when (provider) {
        ServerProvider.CALIBRE_WEB -> R.string.server_provider_calibre
        ServerProvider.KOMGA -> R.string.server_provider_komga
        ServerProvider.BOOKORBIT -> R.string.server_provider_bookorbit
        ServerProvider.OPDS -> R.string.server_provider_opds
    },
)

@Composable
private fun providerDescription(provider: ServerProvider): String = stringResource(
    when (provider) {
        ServerProvider.CALIBRE_WEB -> R.string.server_provider_calibre_desc
        ServerProvider.KOMGA -> R.string.server_provider_komga_desc
        ServerProvider.BOOKORBIT -> R.string.server_provider_bookorbit_desc
        ServerProvider.OPDS -> R.string.server_provider_opds_desc
    },
)

@Composable
private fun failureText(reason: ServerFailure): String = stringResource(
    when (reason) {
        ServerFailure.INVALID_URL -> R.string.server_error_invalid_url
        ServerFailure.NETWORK -> R.string.server_error_network
        ServerFailure.AUTH -> R.string.server_error_auth
        ServerFailure.SERVER -> R.string.server_error_server
        ServerFailure.CERTIFICATE -> R.string.server_error_certificate
        ServerFailure.NOT_OPDS -> R.string.server_error_not_opds
        ServerFailure.NOT_FOUND -> R.string.server_error_not_found
    },
)

/**
 * "Connect server": list of connected servers, a type picker and the sign-in form.
 * [onOpenLibrary] receives the catalog id of the server whose library should be opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConnectSheet(onDismiss: () -> Unit, onOpenLibrary: (catalogId: String) -> Unit) {
    val context = LocalContext.current
    val store = remember { ServerConnectionStore.get(context) }
    val connector = remember { ServerConnector(context.applicationContext, store) }
    val connections by store.connections.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableStateOf(ServerStep.LIST) }
    var provider by rememberSaveable { mutableStateOf(ServerProvider.CALIBRE_WEB) }
    var checking by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf<ServerConnection?>(null) }

    // Update the status of every connection each time the sheet opens.
    LaunchedEffect(Unit) {
        checking = true
        try {
            connector.checkAll()
        } finally {
            checking = false
        }
    }

    disconnecting?.let { target ->
        AlertDialog(
            onDismissRequest = { disconnecting = null },
            title = { Text(stringResource(R.string.server_disconnect_title)) },
            text = { Text(stringResource(R.string.server_disconnect_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    connector.disconnect(target)
                    disconnecting = null
                }) { Text(stringResource(R.string.server_action_disconnect)) }
            },
            dismissButton = { TextButton(onClick = { disconnecting = null }) { Text(stringResource(R.string.server_cancel)) } },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        when (step) {
            ServerStep.LIST -> ServerListContent(
                connections = connections,
                checking = checking,
                onAdd = { step = ServerStep.PICK },
                onOpen = onOpenLibrary,
                onCheck = { connection -> scope.launch { checking = true; try { connector.check(connection) } finally { checking = false } } },
                onDisconnect = { disconnecting = it },
            )
            ServerStep.PICK -> ServerPickContent(
                onBack = { step = ServerStep.LIST },
                onPick = {
                    provider = it
                    step = ServerStep.FORM
                },
            )
            ServerStep.FORM -> ServerFormContent(
                provider = provider,
                connector = connector,
                onBack = { step = ServerStep.PICK },
                onConnected = { step = ServerStep.LIST },
            )
        }
    }
}

@Composable
private fun SheetHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.server_back))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ServerListContent(
    connections: List<ServerConnection>,
    checking: Boolean,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onCheck: (ServerConnection) -> Unit,
    onDisconnect: (ServerConnection) -> Unit,
) {
    Column(
        modifier = Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetHeader(stringResource(R.string.server_title))
        if (connections.isEmpty()) {
            Text(
                stringResource(R.string.server_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        connections.forEach { connection ->
            ServerCard(connection, checking, onOpen, onCheck, onDisconnect)
        }
        FilledTonalButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.server_add))
        }
    }
}

@Composable
private fun ServerCard(
    connection: ServerConnection,
    checking: Boolean,
    onOpen: (String) -> Unit,
    onCheck: (ServerConnection) -> Unit,
    onDisconnect: (ServerConnection) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        providerLabel(connection.provider) + " · " + connection.baseUrl.removePrefix("https://").removePrefix("http://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            StatusChip(connection.status, checking)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onOpen(connection.catalogId) }) {
                    Text(stringResource(R.string.server_action_open))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { onCheck(connection) }, enabled = !checking) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.server_action_check))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onDisconnect(connection) }) {
                    Text(stringResource(R.string.server_action_disconnect), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: ServerStatus, checking: Boolean) {
    val (label, container, content) = when {
        checking && status == ServerStatus.UNKNOWN ->
            Triple(stringResource(R.string.server_status_checking), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        status == ServerStatus.CONNECTED ->
            Triple(stringResource(R.string.server_connected), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        status == ServerStatus.ERROR ->
            Triple(stringResource(R.string.server_status_error), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        else ->
            Triple(stringResource(R.string.server_status_unknown), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(50), color = container) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status == ServerStatus.CONNECTED) {
                Icon(Icons.Default.Check, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

@Composable
private fun ServerPickContent(onBack: () -> Unit, onPick: (ServerProvider) -> Unit) {
    Column(
        modifier = Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SheetHeader(stringResource(R.string.server_pick_title), onBack)
        ServerProvider.entries.forEach { provider ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onPick(provider) },
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(providerLabel(provider), style = MaterialTheme.typography.titleMedium)
                        Text(
                            providerDescription(provider),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ServerFormContent(
    provider: ServerProvider,
    connector: ServerConnector,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(provider) { mutableStateOf("") }
    var address by rememberSaveable(provider) { mutableStateOf("") }
    var username by rememberSaveable(provider) { mutableStateOf("") }
    var password by rememberSaveable(provider) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ServerFailure?>(null) }
    val defaultName = providerLabel(provider)

    fun submit() {
        if (busy || address.isBlank()) return
        scope.launch {
            busy = true
            failure = null
            val result = try {
                connector.connect(provider, name.trim().ifEmpty { defaultName }, address, username, password)
            } finally {
                busy = false
            }
            when (result) {
                is ServerConnectResult.Success -> onConnected()
                is ServerConnectResult.Failure -> failure = result.reason
            }
        }
    }

    Column(
        modifier = Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetHeader(providerLabel(provider), onBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                providerDescription(provider),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; failure = null },
                label = { Text(stringResource(R.string.server_field_address)) },
                placeholder = { Text(stringResource(R.string.server_field_address_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; failure = null },
                label = { Text(stringResource(R.string.server_field_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; failure = null },
                label = { Text(stringResource(R.string.server_field_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.server_field_name)) },
                placeholder = { Text(defaultName) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            failure?.let {
                Text(failureText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = ::submit,
                enabled = !busy && address.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                }
                Text(stringResource(if (busy) R.string.server_status_connecting else R.string.server_action_connect))
            }
        }
    }
}
