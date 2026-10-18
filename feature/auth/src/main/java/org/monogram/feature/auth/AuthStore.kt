package org.monogram.feature.auth

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.AuthCodeType
import org.monogram.core.models.AuthSession
import org.monogram.core.models.AuthState
import org.monogram.core.models.LoadingUi
import org.monogram.network.bridge.session.SessionOps
import org.monogram.network.bridge.session.requiresAuthRestart

interface AuthStore : Store<AuthStore.Intent, AuthStore.State, AuthStore.Label> {
    sealed interface Intent {
        data class PhoneChanged(val value: String) : Intent
        data class CodeChanged(val value: String) : Intent
        data class EmailChanged(val value: String) : Intent
        data class PasswordChanged(val value: String) : Intent
        data class SetTestDc(val enabled: Boolean) : Intent
        data object SendCode : Intent
        data object ResendCode : Intent
        data object SendEmail : Intent
        data object ResetEmail : Intent
        data object SignIn : Intent
        data class SubmitPassword(val password: String) : Intent
        data object BackToPhone : Intent
    }

    sealed interface Phase {
        data object PhoneEntry : Phase
        data class CodeEntry(val challenge: AuthState.AwaitingCode) : Phase
        data class EmailEntry(val challenge: AuthState.AwaitingCode) : Phase
        data object PasswordEntry : Phase
        data class Authorized(val session: AuthSession) : Phase
    }

    sealed interface Error {
        data object PhoneRequired : Error
        data object CodeRequired : Error
        data object EmailRequired : Error
        data object PasswordRequired : Error
        data class Rpc(val error: TelegramError) : Error
    }

    data class State(
        val phone: String = "",
        val code: String = "",
        val email: String = "",
        val password: String = "",
        val phase: Phase = Phase.PhoneEntry,
        val loading: Boolean = false,
        val error: Error? = null,
        val useTestDc: Boolean = false,
        val loadGeneration: Int = 0,
        val nowSeconds: Long = 0,
        val blockedUntil: Long = 0,
    ) {
        val loadingUi: LoadingUi get() = LoadingUi.flag(visible = loading, generation = loadGeneration)
        val challenge: AuthState.AwaitingCode? get() = when (val current = phase) {
            is Phase.CodeEntry -> current.challenge
            is Phase.EmailEntry -> current.challenge
            else -> null
        }
        val retrySeconds: Long get() = (maxOf(challenge?.resendAvailableAt ?: 0, blockedUntil) - nowSeconds).coerceAtLeast(0)
        val canResend: Boolean get() = !loading && phase is Phase.CodeEntry && challenge?.canResend == true && retrySeconds == 0L
        val resetSeconds: Long get() = (maxOf(challenge?.emailResetPendingDate ?: challenge?.emailResetAvailableAt ?: 0, blockedUntil) - nowSeconds).coerceAtLeast(0)
        val canResetEmail: Boolean get() = !loading && challenge?.codeType == AuthCodeType.Email &&
            (challenge?.emailResetAvailableAt != null || challenge?.emailResetPendingDate != null) && resetSeconds == 0L
    }

    sealed interface Label {
        data class Authorized(val session: AuthSession) : Label
    }
}

internal class AuthStoreFactory(
    private val storeFactory: StoreFactory,
    private val client: SessionOps,
    private val clock: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    fun create(): AuthStore = object : AuthStore,
        Store<AuthStore.Intent, AuthStore.State, AuthStore.Label> by storeFactory.create(
            name = "AuthStore",
            initialState = AuthStore.State(loading = true, nowSeconds = clock()),
            bootstrapper = SimpleBootstrapper(Unit),
            executorFactory = { ExecutorImpl() },
            reducer = ReducerImpl,
        ) {}

    private sealed interface Msg {
        data class Phone(val value: String) : Msg
        data class Code(val value: String) : Msg
        data class Email(val value: String) : Msg
        data class Password(val value: String) : Msg
        data class Loading(val value: Boolean) : Msg
        data class Error(val value: AuthStore.Error?) : Msg
        data class Auth(val value: AuthState) : Msg
        data class UseTestDc(val value: Boolean) : Msg
        data class Time(val now: Long) : Msg
        data class Blocked(val until: Long) : Msg
        data object BackToPhone : Msg
    }

    private inner class ExecutorImpl : CoroutineExecutor<AuthStore.Intent, Unit, AuthStore.State, Msg, AuthStore.Label>() {
        override fun executeAction(action: Unit) {
            scope.launch {
                while (true) {
                    dispatch(Msg.Time(clock()))
                    delay(1_000)
                }
            }
            scope.launch {
                when (val test = client.usesTestDc()) {
                    is Outcome.Ok -> dispatch(Msg.UseTestDc(test.value))
                    is Outcome.Err -> Unit
                }
                handle(client.restoreAuth())
                dispatch(Msg.Loading(false))
            }
        }

        override fun executeIntent(intent: AuthStore.Intent) {
            when (intent) {
                is AuthStore.Intent.PhoneChanged -> if (!state().loading) dispatch(Msg.Phone(intent.value))
                is AuthStore.Intent.CodeChanged -> if (!state().loading) {
                    state().challenge?.let { dispatch(Msg.Code(filterAuthCode(intent.value, it))) }
                }
                is AuthStore.Intent.EmailChanged -> if (!state().loading) dispatch(Msg.Email(intent.value))
                is AuthStore.Intent.PasswordChanged -> dispatch(Msg.Password(intent.value))
                is AuthStore.Intent.SetTestDc -> if (!state().loading) dispatch(Msg.UseTestDc(intent.enabled))
                AuthStore.Intent.SendCode -> sendCode()
                AuthStore.Intent.ResendCode -> {
                    dispatch(Msg.Time(clock()))
                    val challenge = state().challenge ?: return
                    if (state().canResend) request { client.resendAuthCode(challenge.phone, challenge.phoneCodeHash) }
                }
                AuthStore.Intent.SendEmail -> {
                    val phase = state().phase as? AuthStore.Phase.EmailEntry ?: return
                    val email = state().email.trim()
                    if (email.isEmpty()) dispatch(Msg.Error(AuthStore.Error.EmailRequired))
                    else request { client.sendAuthEmail(phase.challenge.phone, phase.challenge.phoneCodeHash, email) }
                }
                AuthStore.Intent.ResetEmail -> {
                    dispatch(Msg.Time(clock()))
                    val challenge = state().challenge ?: return
                    if (state().canResetEmail) request { client.resetAuthEmail(challenge.phone, challenge.phoneCodeHash) }
                }
                AuthStore.Intent.SignIn -> {
                    val phase = state().phase as? AuthStore.Phase.CodeEntry ?: return
                    val code = state().code.trim()
                    if (code.isEmpty()) dispatch(Msg.Error(AuthStore.Error.CodeRequired))
                    else request { client.signIn(phase.challenge.phone, phase.challenge.phoneCodeHash, code) }
                }
                is AuthStore.Intent.SubmitPassword -> {
                    if (state().phase !is AuthStore.Phase.PasswordEntry) return
                    val password = intent.password.ifEmpty { state().password }
                    if (password.isEmpty()) dispatch(Msg.Error(AuthStore.Error.PasswordRequired))
                    else request { client.checkPassword(password) }
                }
                AuthStore.Intent.BackToPhone -> if (!state().loading) {
                    dispatch(Msg.Loading(true))
                    scope.launch {
                        when (val cleared = client.cancelAuth()) {
                            is Outcome.Ok -> dispatch(Msg.BackToPhone)
                            is Outcome.Err -> handle(cleared)
                        }
                        dispatch(Msg.Loading(false))
                    }
                }
            }
        }

        private fun sendCode() {
            if (state().phase !is AuthStore.Phase.PhoneEntry) return
            val phone = normalizePhone(state().phone)
            if (phone.isEmpty()) {
                dispatch(Msg.Error(AuthStore.Error.PhoneRequired))
                return
            }
            val testDc = state().useTestDc
            dispatch(Msg.Phone(phone))
            request {
                when (val test = client.setTestDc(testDc)) {
                    is Outcome.Err -> test
                    is Outcome.Ok -> client.sendAuthCode(phone)
                }
            }
        }

        private fun request(block: suspend () -> Outcome<AuthState>) {
            if (state().loading || clock() < state().blockedUntil) return
            dispatch(Msg.Loading(true))
            dispatch(Msg.Error(null))
            scope.launch {
                try {
                    handle(block())
                } finally {
                    dispatch(Msg.Loading(false))
                }
            }
        }

        private fun handle(result: Outcome<AuthState>) {
            dispatch(Msg.Time(clock()))
            when (result) {
                is Outcome.Ok -> {
                    val auth = result.value
                    dispatch(Msg.Auth(auth))
                    if (auth is AuthState.Authorized) publish(AuthStore.Label.Authorized(auth.session))
                }
                is Outcome.Err -> {
                    val error = result.telegramError
                    if (error.requiresAuthRestart()) dispatch(Msg.BackToPhone)
                    error.retryAfterSeconds?.let { dispatch(Msg.Blocked(clock() + it)) }
                    dispatch(Msg.Error(AuthStore.Error.Rpc(error)))
                }
            }
        }
    }

    private object ReducerImpl : Reducer<AuthStore.State, Msg> {
        override fun AuthStore.State.reduce(msg: Msg): AuthStore.State = when (msg) {
            is Msg.Phone -> copy(phone = msg.value, error = null)
            is Msg.Code -> copy(code = msg.value, error = null)
            is Msg.Email -> copy(email = msg.value, error = null)
            is Msg.Password -> copy(password = msg.value, error = null)
            is Msg.Loading -> copy(loading = msg.value, loadGeneration = loadGeneration + if (msg.value) 1 else 0)
            is Msg.Error -> copy(error = msg.value)
            is Msg.UseTestDc -> copy(useTestDc = msg.value, error = null)
            is Msg.Time -> copy(nowSeconds = msg.now)
            is Msg.Blocked -> copy(blockedUntil = msg.until)
            Msg.BackToPhone -> copy(phase = AuthStore.Phase.PhoneEntry, code = "", email = "", password = "", error = null)
            is Msg.Auth -> when (val auth = msg.value) {
                AuthState.LoggedOut -> copy(phase = AuthStore.Phase.PhoneEntry, code = "", password = "", error = null)
                is AuthState.AwaitingCode -> copy(
                    phone = auth.phone, code = "", password = "", email = auth.emailAddress.orEmpty(), error = null,
                    phase = if (auth.codeType == AuthCodeType.EmailSetup) AuthStore.Phase.EmailEntry(auth) else AuthStore.Phase.CodeEntry(auth),
                )
                is AuthState.AwaitingPassword -> copy(phone = auth.phone.ifEmpty { phone }, phase = AuthStore.Phase.PasswordEntry, code = "", password = "", error = null)
                is AuthState.Authorized -> copy(phase = AuthStore.Phase.Authorized(auth.session), code = "", password = "", email = "", error = null)
            }
        }
    }
}

internal fun filterAuthCode(raw: String, challenge: AuthState.AwaitingCode): String = when {
    challenge.codeType.isText -> raw
    challenge.codeLength > 0 -> raw.filter(Char::isDigit).take(challenge.codeLength)
    else -> raw.filter(Char::isDigit)
}

internal fun normalizePhone(raw: String): String {
    val compact = raw.trim().replace(" ", "")
    if (compact.isEmpty() || compact == "+") return ""
    return if (compact.startsWith("+")) compact else "+$compact"
}
