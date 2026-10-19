package org.monogram.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.components.SettingsCard
import org.monogram.core.ui.components.SettingsCardRow
import org.monogram.core.ui.components.SettingsTile
import org.monogram.core.ui.components.listItemMotion
import org.monogram.core.ui.loading.MonogramLinearProgress
import org.monogram.feature.settings.DevicesStore
import org.monogram.feature.settings.R
import org.monogram.feature.settings.SettingsComponent
import org.monogram.feature.settings.SettingsPage
import org.monogram.network.bridge.session.LoginTokenFailure

@Composable
internal fun DevicesSettings(component: SettingsComponent, modifier: Modifier) {
    val state by component.devices.collectAsStateWithLifecycle()
    LifecycleResumeEffect(component) {
        component.onDevices(DevicesStore.Intent.Resume)
        component.onDevices(DevicesStore.Intent.Refresh)
        onPauseOrDispose { component.onDevices(DevicesStore.Intent.Pause) }
    }
    DevicesSettingsContent(
        state = state,
        onIntent = component::onDevices,
        onScan = { component.openPage(SettingsPage.DeviceScanner) },
        modifier = modifier,
    )
}

@Composable
internal fun DevicesSettingsContent(
    state: DevicesStore.State,
    onIntent: (DevicesStore.Intent) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var revokeHash by remember { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val title = stringResource(R.string.settings_devices_linked)
    val terminate = stringResource(R.string.settings_devices_terminate_action)
    LaunchedEffect(state.linkedSession?.hash) {
        val session = state.linkedSession ?: return@LaunchedEffect
        if (
            snackbar.showSnackbar(
                message = session.appName,
                actionLabel = terminate,
                duration = SnackbarDuration.Long,
            ) == SnackbarResult.ActionPerformed
        ) {
            onIntent(DevicesStore.Intent.Revoke(session.hash))
        }
        onIntent(DevicesStore.Intent.DismissLinked(session.hash))
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsTile(
                    icon = Icons.Outlined.QrCodeScanner,
                    iconColor = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.settings_devices_link),
                    subtitle = stringResource(R.string.settings_devices_scan_hint),
                    onClick = onScan,
                )
            }
            if (state.loading) item { MonogramLinearProgress(modifier = Modifier.fillMaxWidth()) }
            state.error?.let { error ->
                item {
                    AppStatusBanner(
                        sync = AppSyncStatus.Hidden,
                        error = error,
                        onRetry = { onIntent(DevicesStore.Intent.Refresh) },
                    )
                }
            }
            state.tokenFailure?.let { failure ->
                item {
                    Text(
                        stringResource(
                            when (failure) {
                                LoginTokenFailure.Invalid -> R.string.settings_devices_invalid
                                LoginTokenFailure.Expired -> R.string.settings_devices_expired
                                LoginTokenFailure.AlreadyAccepted ->
                                    R.string.settings_devices_already_accepted
                                LoginTokenFailure.AlreadyLoggedIn ->
                                    R.string.settings_devices_already_logged_in
                            }
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                TextButton(
                    onClick = { onIntent(DevicesStore.Intent.Refresh) },
                    enabled = !state.loading,
                ) {
                    Text(stringResource(R.string.settings_devices_refresh))
                }
            }
            if (state.sessions.isEmpty() && !state.loading && state.error == null)
                item { Text(stringResource(R.string.settings_devices_empty)) }
            items(state.sessions, key = { it.hash }) { session ->
                SettingsCard(modifier = listItemMotion()) {
                    SettingsCardRow(
                        icon = Icons.Outlined.Devices,
                        iconColor = MaterialTheme.colorScheme.secondary,
                        title = session.deviceModel,
                        subtitle =
                            listOf(
                                    "${session.appName} ${session.appVersion}",
                                    "${session.platform} ${session.systemVersion}",
                                    listOf(session.ip, session.country, session.region)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    when {
                                        session.current ->
                                            stringResource(R.string.settings_devices_current)
                                        session.passwordPending ->
                                            stringResource(
                                                R.string.settings_devices_password_pending
                                            )
                                        else ->
                                            stringResource(
                                                R.string.settings_devices_last_active,
                                                DateFormat.getDateTimeInstance(
                                                        DateFormat.MEDIUM,
                                                        DateFormat.SHORT,
                                                    )
                                                    .format(Date(session.dateActive * 1000L)),
                                            )
                                    },
                                )
                                .filter { it.isNotBlank() }
                                .joinToString("\n"),
                        trailingContent =
                            if (session.current) null
                            else {
                                {
                                    IconButton(
                                        onClick = { revokeHash = session.hash },
                                        enabled = !state.loading,
                                        colors =
                                            IconButtonDefaults.iconButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error
                                            ),
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.Logout,
                                            contentDescription =
                                                stringResource(R.string.settings_devices_terminate),
                                        )
                                    }
                                }
                            },
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) { data ->
            Snackbar(
                action = {
                    TextButton(
                        onClick = data::performAction,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = SnackbarDefaults.actionColor
                            ),
                    ) {
                        Text(data.visuals.actionLabel.orEmpty())
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    revokeHash?.let { hash ->
        AlertDialog(
            onDismissRequest = { revokeHash = null },
            title = { Text(stringResource(R.string.settings_devices_terminate)) },
            text = { Text(stringResource(R.string.settings_devices_terminate_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        revokeHash = null
                        onIntent(DevicesStore.Intent.Revoke(hash))
                    }
                ) {
                    Text(stringResource(R.string.settings_devices_terminate))
                }
            },
            dismissButton = {
                TextButton(onClick = { revokeHash = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}
