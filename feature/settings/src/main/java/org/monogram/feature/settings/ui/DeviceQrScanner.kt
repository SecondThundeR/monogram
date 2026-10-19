package org.monogram.feature.settings.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.monogram.core.ui.components.QrCodeScreen
import org.monogram.feature.settings.R

@Composable
internal fun DeviceQrScanner(onScanned: (String) -> Unit, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val onResult by rememberUpdatedState(onScanned)
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraFailed by remember { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
        }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }
    QrCodeScreen(
        title = stringResource(R.string.settings_devices_scan_title),
        description = stringResource(R.string.settings_devices_scan_hint),
        icon = Icons.Outlined.QrCodeScanner,
        backDescription = stringResource(R.string.settings_back),
        onBack = onBack,
        modifier = modifier,
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(8.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(32.dp))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (granted && !cameraFailed) {
                DeviceQrCamera(
                    onScanned = { result ->
                        if (!delivered) {
                            delivered = true
                            onResult(result)
                        }
                    },
                    onFailure = { cameraFailed = true },
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)),
                )
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(
                            if (cameraFailed) R.string.settings_devices_camera_unavailable
                            else R.string.settings_devices_camera_permission
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = {
                            if (cameraFailed) cameraFailed = false
                            else
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    )
                                )
                        }
                    ) {
                        Text(
                            stringResource(
                                if (cameraFailed) R.string.settings_devices_refresh
                                else R.string.settings_devices_open_settings
                            )
                        )
                    }
                }
            }
        }
    }
}
