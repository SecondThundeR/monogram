package org.monogram.feature.auth.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.loading.MonogramBusyButton
import org.monogram.core.ui.loading.MonogramLinearProgress
import org.monogram.core.models.AuthCodeType
import org.monogram.core.models.AuthState
import org.monogram.feature.auth.AuthStore
import org.monogram.feature.auth.R
import org.monogram.feature.auth.normalizePhone

internal fun authStepOf(phase: AuthStore.Phase): Int? = when (phase) {
    AuthStore.Phase.PhoneEntry -> 0
    is AuthStore.Phase.CodeEntry, is AuthStore.Phase.EmailEntry -> 1
    AuthStore.Phase.PasswordEntry -> 2
    is AuthStore.Phase.Authorized -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AuthScreen(
    state: AuthStore.State,
    onIntent: (AuthStore.Intent) -> Unit,
    onOpenProxy: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val step = authStepOf(state.phase)
    if (step == null) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }
    val motion = authMotionEnabled()
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val phoneFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    var passwordVisible by rememberSaveable(state.phase) { mutableStateOf(false) }
    var pasteEmpty by rememberSaveable(state.phase) { mutableStateOf(false) }
    var submittedCode by remember { mutableStateOf("") }
    var showResetEmail by remember { mutableStateOf(false) }
    val challenge = state.challenge
    val expectedCodeLength = challenge?.codeLength ?: AuthCodeLength
    LaunchedEffect(state.phase) {
        submittedCode = ""
    }
    LaunchedEffect(state.code, state.loading, state.phase, expectedCodeLength) {
        val inCode = state.phase is AuthStore.Phase.CodeEntry
        if (inCode && expectedCodeLength > 0 &&
            challenge?.codeType != AuthCodeType.Firebase &&
            state.code.length == expectedCodeLength &&
            !state.loading && state.nowSeconds >= state.blockedUntil &&
            state.code != submittedCode
        ) {
            submittedCode = state.code
            onIntent(AuthStore.Intent.SignIn)
        }
    }
    LaunchedEffect(step) {
        when (step) {
            0 -> phoneFocus.requestFocus()
            2 -> passwordFocus.requestFocus()
        }
    }

    val canGoBack = state.phase is AuthStore.Phase.CodeEntry || state.phase is AuthStore.Phase.EmailEntry ||
        state.phase is AuthStore.Phase.PasswordEntry
    val fieldError = state.error != null && !state.loading
    val errorMessage = state.error?.let { errorText(it) }
    val pasteMessage = if (pasteEmpty) stringResource(R.string.auth_paste_empty) else null
    val primaryEnabled = !state.loading && state.nowSeconds >= state.blockedUntil && when (state.phase) {
        is AuthStore.Phase.EmailEntry -> state.email.isNotBlank()
        is AuthStore.Phase.CodeEntry -> challenge?.codeType != AuthCodeType.Firebase &&
            if (expectedCodeLength > 0) state.code.length == expectedCodeLength else state.code.isNotBlank()
        AuthStore.Phase.PasswordEntry -> state.password.isNotEmpty()
        else -> normalizePhone(state.phone).isNotEmpty()
    }
    val actionLabel = if (state.blockedUntil > state.nowSeconds) {
        stringResource(R.string.auth_retry_in, state.blockedUntil - state.nowSeconds)
    } else stringResource(
        when (state.phase) {
            is AuthStore.Phase.EmailEntry -> R.string.auth_send_email
            is AuthStore.Phase.CodeEntry -> R.string.auth_sign_in
            AuthStore.Phase.PasswordEntry -> R.string.auth_submit_password
            else -> R.string.auth_send_code
        },
    )

    Scaffold(
        modifier = modifier.fillMaxSize().testTag(AuthTestTags.ROOT),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (canGoBack) {
                        val back = stringResource(R.string.auth_cd_back)
                        IconButton(
                            onClick = { onIntent(AuthStore.Intent.BackToPhone) },
                            enabled = !state.loading,
                            modifier = Modifier.semantics { contentDescription = back },
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = back)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    if (onOpenProxy != null) {
                        val label = stringResource(R.string.auth_connection)
                        IconButton(
                            onClick = onOpenProxy,
                            modifier = Modifier
                                .testTag(AuthTestTags.PROXY)
                                .semantics { contentDescription = label },
                        ) {
                            Icon(Icons.Outlined.VpnKey, contentDescription = label)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MonogramBusyButton(
                        onClick = {
                            when (state.phase) {
                                is AuthStore.Phase.EmailEntry ->
                                    onIntent(AuthStore.Intent.SendEmail)
                                is AuthStore.Phase.CodeEntry ->
                                    onIntent(AuthStore.Intent.SignIn)
                                AuthStore.Phase.PasswordEntry ->
                                    onIntent(AuthStore.Intent.SubmitPassword(state.password))
                                else -> onIntent(AuthStore.Intent.SendCode)
                            }
                        },
                        busy = state.loadingUi.visible,
                        enabled = primaryEnabled,
                        modifier = Modifier
                            .widthIn(max = AuthFormMaxWidth)
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag(AuthTestTags.PRIMARY),
                        shapes = ExpressiveDefaults.largeButtonShapes(),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = AuthFormMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MonogramLinearProgress(
                    visible = state.loadingUi.visible,
                    modifier = Modifier.fillMaxWidth(),
                    height = 3.dp,
                    generation = state.loadingUi.generation,
                )
                Spacer(modifier = Modifier.height(24.dp))
                AuthStepIndicator(step = step, motion = motion)
                Spacer(modifier = Modifier.height(28.dp))
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        authStepTransition(motion = motion, forward = targetState > initialState)
                    },
                    label = "auth-step",
                ) { active ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AuthStepHeader(step = active, state = state)
                        when (active) {
                            1 -> {
                                val emailEntry = state.phase is AuthStore.Phase.EmailEntry
                                if (emailEntry || expectedCodeLength <= 0 || challenge?.codeType?.isText == true) {
                                    OutlinedTextField(
                                        value = if (emailEntry) state.email else state.code,
                                        onValueChange = {
                                            pasteEmpty = false
                                            onIntent(if (emailEntry) AuthStore.Intent.EmailChanged(it) else AuthStore.Intent.CodeChanged(it))
                                        },
                                        label = { Text(stringResource(if (emailEntry) R.string.auth_email_label else R.string.auth_code_label)) },
                                        enabled = !state.loading,
                                        isError = fieldError,
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            autoCorrectEnabled = false,
                                            keyboardType = if (emailEntry) KeyboardType.Email else if (challenge?.codeType?.isText == true) KeyboardType.Text else KeyboardType.Phone,
                                            imeAction = ImeAction.Done,
                                        ),
                                        keyboardActions = KeyboardActions(onDone = {
                                            if (primaryEnabled) onIntent(if (emailEntry) AuthStore.Intent.SendEmail else AuthStore.Intent.SignIn)
                                        }),
                                        modifier = Modifier.fillMaxWidth().testTag(if (emailEntry) "auth_email_field" else AuthTestTags.CODE_FIELD),
                                    )
                                } else {
                                    AuthOtpField(
                                        value = state.code,
                                        codeLength = expectedCodeLength,
                                        onValueChange = { raw ->
                                            pasteEmpty = false
                                            onIntent(AuthStore.Intent.CodeChanged(raw))
                                        },
                                        enabled = !state.loading,
                                        isError = fieldError,
                                    )
                                }
                            }
                            2 -> AuthPasswordField(
                                value = state.password,
                                visible = passwordVisible,
                                enabled = !state.loading,
                                isError = fieldError,
                                focus = passwordFocus,
                                onValueChange = { value ->
                                    pasteEmpty = false
                                    onIntent(AuthStore.Intent.PasswordChanged(value))
                                },
                                onToggleVisible = { passwordVisible = !passwordVisible },
                                onPaste = {
                                    scope.launch {
                                        val text = clipboard.getClipEntry()
                                            ?.clipData
                                            ?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)
                                            ?.text
                                            ?.toString()
                                            .orEmpty()
                                        pasteEmpty = text.isEmpty()
                                        if (text.isNotEmpty()) {
                                            onIntent(AuthStore.Intent.PasswordChanged(text))
                                        }
                                    }
                                },
                                onImeAction = {
                                    if (primaryEnabled) {
                                        onIntent(AuthStore.Intent.SubmitPassword(state.password))
                                    }
                                },
                            )
                            else -> AuthPhoneStep(
                                state = state,
                                focus = phoneFocus,
                                enabled = !state.loading,
                                isError = fieldError && state.error is AuthStore.Error.PhoneRequired,
                                onIntent = onIntent,
                                primaryEnabled = primaryEnabled,
                            )
                        }
                        AuthInlineError(message = errorMessage ?: pasteMessage)
                        if (active == 1 && state.phase is AuthStore.Phase.CodeEntry) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val text = clipboard.getClipEntry()
                                            ?.clipData
                                            ?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)
                                            ?.text
                                            ?.toString()
                                            .orEmpty()
                                        pasteEmpty = text.isEmpty()
                                        val code = text
                                        if (code.isNotEmpty()) {
                                            onIntent(AuthStore.Intent.CodeChanged(code))
                                        }
                                    }
                                },
                                enabled = !state.loading,
                                modifier = Modifier.testTag(AuthTestTags.PASTE_CODE),
                            ) {
                                Text(stringResource(R.string.auth_paste_code))
                            }
                            if (challenge?.canResend == true) {
                                TextButton(
                                    onClick = { submittedCode = ""; onIntent(AuthStore.Intent.ResendCode) },
                                    enabled = state.canResend,
                                    modifier = Modifier.testTag(AuthTestTags.CODE_HELP),
                                ) {
                                    Text(if (state.retrySeconds > 0) stringResource(R.string.auth_resend_in, state.retrySeconds)
                                        else stringResource(R.string.auth_resend_code))
                                }
                            } else {
                                Text(stringResource(R.string.auth_no_alternative), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            }
                            if (challenge?.codeType == AuthCodeType.Fragment && challenge.fragmentUrl.isNotEmpty()) {
                                TextButton(onClick = { runCatching { uriHandler.openUri(challenge.fragmentUrl) } }) {
                                    Text(stringResource(R.string.auth_open_fragment))
                                }
                            }
                            if (challenge?.codeType == AuthCodeType.Email) {
                                val pendingDate = challenge.emailResetPendingDate
                                if (pendingDate != null) {
                                    Text(
                                        stringResource(R.string.auth_email_reset_pending,
                                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(pendingDate * 1_000))),
                                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                                    )
                                }
                                if (challenge.emailResetAvailableAt != null || pendingDate != null && pendingDate <= state.nowSeconds) {
                                    TextButton(onClick = { showResetEmail = true }, enabled = state.canResetEmail) {
                                        Text(if (state.resetSeconds > 0) stringResource(R.string.auth_email_reset_in, state.resetSeconds)
                                            else stringResource(R.string.auth_email_reset))
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
    if (showResetEmail) {
        AlertDialog(
            onDismissRequest = { showResetEmail = false },
            title = { Text(stringResource(R.string.auth_email_reset)) },
            text = { Text(stringResource(R.string.auth_email_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = { showResetEmail = false; onIntent(AuthStore.Intent.ResetEmail) }) {
                    Text(stringResource(R.string.auth_email_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetEmail = false }) { Text(stringResource(R.string.auth_cancel)) }
            },
        )
    }
}

@Composable
private fun AuthStepIndicator(step: Int, motion: Boolean, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.auth_step_indicator, step + 1, AuthStepCount)
    Row(
        modifier = modifier
            .testTag(AuthTestTags.STEP_INDICATOR)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(AuthStepCount) { index ->
            val active = index == step
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                animationSpec = if (motion) tween(260, easing = AuthExpressiveEasing) else snap(),
                label = "auth-step-dot",
            )
            Box(
                modifier = Modifier
                    .width(width)
                    .height(8.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = RoundedCornerShape(percent = 50),
                    ),
            )
        }
    }
}

@Composable
private fun AuthStepHeader(step: Int, state: AuthStore.State) {
    val title = stringResource(
        when (step) {
            1 -> if (state.phase is AuthStore.Phase.EmailEntry) R.string.auth_email_title else R.string.auth_code_title
            2 -> R.string.auth_password_title
            else -> R.string.auth_title
        },
    )
    val subtitle = if (step == 1) {
        val code = state.challenge
        if (code != null) codeSentMessage(code) else stringResource(R.string.auth_code_title)
    } else if (step == 2) {
        stringResource(R.string.auth_password_prompt)
    } else {
        stringResource(R.string.auth_subtitle)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AuthTestTags.TITLE),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun authStepTransition(motion: Boolean, forward: Boolean): ContentTransform {
    if (!motion) {
        return fadeIn(animationSpec = tween(0)) togetherWith fadeOut(animationSpec = tween(0))
    }
    val direction = if (forward) 1 else -1
    return (
        slideInHorizontally(animationSpec = tween(320, easing = AuthExpressiveEasing)) {
            it / 6 * direction
        } + fadeIn(animationSpec = tween(220))
        ) togetherWith (
        slideOutHorizontally(animationSpec = tween(220, easing = AuthExpressiveEasing)) {
            -it / 6 * direction
        } + fadeOut(animationSpec = tween(140))
        )
}

@Composable
private fun authMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

@Composable
private fun codeSentMessage(challenge: AuthState.AwaitingCode): String {
    val phone = normalizePhone(challenge.phone).ifEmpty { challenge.phone }
    return when (challenge.codeType) {
        AuthCodeType.App -> stringResource(R.string.auth_code_sent_telegram)
        AuthCodeType.Sms -> stringResource(R.string.auth_code_sent_sms, phone)
        AuthCodeType.Call -> stringResource(R.string.auth_code_sent_call, phone)
        AuthCodeType.FlashCall -> stringResource(R.string.auth_code_flash_call, challenge.phonePattern)
        AuthCodeType.MissedCall -> stringResource(R.string.auth_code_missed_call, challenge.phonePattern, challenge.codeLength)
        AuthCodeType.Email, AuthCodeType.EmailSetupCode -> stringResource(R.string.auth_code_sent_email, challenge.emailPattern)
        AuthCodeType.EmailSetup -> stringResource(R.string.auth_email_prompt)
        AuthCodeType.Fragment -> stringResource(R.string.auth_code_fragment)
        AuthCodeType.Firebase -> stringResource(R.string.auth_firebase_unavailable)
        AuthCodeType.SmsWord -> if (challenge.beginning.isEmpty()) stringResource(R.string.auth_code_sms_word)
            else stringResource(R.string.auth_code_sms_word_hint, challenge.beginning)
        AuthCodeType.SmsPhrase -> if (challenge.beginning.isEmpty()) stringResource(R.string.auth_code_sms_phrase)
            else stringResource(R.string.auth_code_sms_phrase_hint, challenge.beginning)
    }
}
