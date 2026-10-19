package org.monogram.feature.auth.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.core.ui.components.QrCodeScreen
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.qr.QrCode
import org.monogram.feature.auth.AuthStore
import org.monogram.feature.auth.R

@Composable
internal fun AuthQrScreen(
    state: AuthStore.State,
    onIntent: (AuthStore.Intent) -> Unit,
    modifier: Modifier,
) {
    val onBack = { onIntent(AuthStore.Intent.BackToPhone) }
    BackHandler(onBack = onBack)
    val code = (state.phase as? AuthStore.Phase.QrEntry)?.code
    val colors = MaterialTheme.colorScheme
    val background =
        if (colors.surfaceContainerHighest.luminance() > colors.onSurface.luminance()) {
            colors.surfaceContainerHighest
        } else {
            colors.onSurface
        }
    val foreground =
        if (colors.primary.luminance() < colors.inversePrimary.luminance()) {
            colors.primary
        } else {
            colors.inversePrimary
        }
    val image by
        produceState<ImageBitmap?>(null, code, foreground, background) {
            if (code == null) {
                value = null
            } else {
                value =
                    withContext(Dispatchers.Default) {
                        val ink = foreground.toArgb()
                        val bitmap = QrCode.render(code.link, 768, ink, background.toArgb())
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        // The square QR is centered, so its first dark row marks the border inset.
                        val inset = pixels.indexOfFirst { it == ink } / bitmap.width
                        val side = bitmap.width - inset * 2
                        Bitmap.createBitmap(bitmap, inset, inset, side, side).asImageBitmap()
                    }
            }
        }
    QrCodeScreen(
        title = stringResource(R.string.auth_qr_login),
        description = stringResource(R.string.auth_qr_instructions),
        icon = Icons.Outlined.QrCode2,
        backDescription = stringResource(R.string.auth_cd_back),
        onBack = onBack,
        modifier = modifier,
        descriptionAlignment = TextAlign.Center,
    ) {
        BoxWithConstraints(
            Modifier.padding(horizontal = 16.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            val qrImage = image.takeIf { code != null }
            if (qrImage != null) {
                Image(
                    qrImage,
                    contentDescription = stringResource(R.string.auth_qr_code),
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    filterQuality = FilterQuality.None,
                )
            } else if (state.error == null) {
                MonogramLoading(size = maxWidth / 2, color = foreground)
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AuthInlineError(state.error?.let { errorText(it) })
            if (state.error != null) {
                Button(
                    onClick = { onIntent(AuthStore.Intent.StartQrLogin) },
                    enabled = !state.loading,
                ) {
                    Text(stringResource(R.string.auth_qr_retry))
                }
            }
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.auth_qr_use_phone))
            }
        }
    }
}
