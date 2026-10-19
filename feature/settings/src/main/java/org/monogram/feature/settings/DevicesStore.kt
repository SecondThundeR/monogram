package org.monogram.feature.settings

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.DeviceSession
import org.monogram.network.bridge.session.LoginTokenFailure
import org.monogram.network.bridge.session.SessionOps
import org.monogram.network.bridge.session.loginTokenFailure

interface DevicesStore : Store<DevicesStore.Intent, DevicesStore.State, Nothing> {
    sealed interface Intent {
        data object Resume : Intent

        data object Pause : Intent

        data object Refresh : Intent

        class Accept(val link: String) : Intent

        data class Revoke(val hash: Long) : Intent

        data class DismissLinked(val hash: Long) : Intent
    }

    data class State(
        val sessions: List<DeviceSession> = emptyList(),
        val loading: Boolean = false,
        val error: TelegramError? = null,
        val tokenFailure: LoginTokenFailure? = null,
        val linkedSession: DeviceSession? = null,
    )
}

internal class DevicesStoreFactory(
    private val factory: StoreFactory,
    private val client: SessionOps,
) {
    fun create(): DevicesStore =
        object :
            DevicesStore,
            Store<DevicesStore.Intent, DevicesStore.State, Nothing> by factory.create(
                name = "DevicesStore",
                initialState = DevicesStore.State(),
                executorFactory = { Executor() },
                reducer = Reducer<DevicesStore.State, DevicesStore.State> { it },
            ) {}

    private inner class Executor :
        CoroutineExecutor<
            DevicesStore.Intent,
            Nothing,
            DevicesStore.State,
            DevicesStore.State,
            Nothing,
        >() {
        private val operationMutex = Mutex()
        private var resumed = false
        private var pollingJob: Job? = null
        private var refreshFailed = false

        override fun executeIntent(intent: DevicesStore.Intent) {
            if (intent == DevicesStore.Intent.Resume) {
                resumed = true
                watchPendingSessions()
                return
            }
            if (intent == DevicesStore.Intent.Pause) {
                resumed = false
                pollingJob?.cancel()
                pollingJob = null
                return
            }
            if (intent is DevicesStore.Intent.DismissLinked) {
                if (state().linkedSession?.hash == intent.hash) {
                    dispatch(state().copy(linkedSession = null))
                }
                return
            }
            if (intent is DevicesStore.Intent.Refresh && state().loading) return
            scope.launch {
                operationMutex.withLock {
                    if (
                        intent is DevicesStore.Intent.Revoke &&
                            state().sessions.none { it.hash == intent.hash && !it.current }
                    )
                        return@withLock
                    refreshFailed = false
                    dispatch(state().copy(loading = true, error = null, tokenFailure = null))
                    when (intent) {
                        DevicesStore.Intent.Refresh -> refresh()
                        is DevicesStore.Intent.Accept ->
                            when (val result = client.acceptLoginToken(intent.link)) {
                                is Outcome.Ok -> {
                                    val session = result.value
                                    dispatch(
                                        state()
                                            .copy(
                                                sessions =
                                                    (state().sessions.filterNot {
                                                            it.hash == session.hash
                                                        } + session)
                                                        .ordered(),
                                                linkedSession = session,
                                            )
                                    )
                                }
                                is Outcome.Err -> failure(result)
                            }
                        is DevicesStore.Intent.Revoke ->
                            when (val result = client.revokeDeviceSession(intent.hash)) {
                                is Outcome.Ok ->
                                    dispatch(
                                        state()
                                            .copy(
                                                sessions =
                                                    state().sessions.filterNot {
                                                        it.hash == intent.hash
                                                    },
                                                linkedSession =
                                                    state().linkedSession?.takeUnless {
                                                        it.hash == intent.hash
                                                    },
                                            )
                                    )
                                is Outcome.Err -> failure(result)
                            }
                        is DevicesStore.Intent.DismissLinked,
                        DevicesStore.Intent.Resume,
                        DevicesStore.Intent.Pause -> Unit
                    }
                    dispatch(state().copy(loading = false))
                    watchPendingSessions()
                }
            }
        }

        private fun watchPendingSessions() {
            if (
                !resumed ||
                    pollingJob?.isActive == true ||
                    state().sessions.none { it.passwordPending }
            )
                return
            pollingJob = scope.launch {
                while (state().sessions.any { it.passwordPending }) {
                    delay(1500)
                    operationMutex.withLock {
                        if (state().sessions.none { it.passwordPending }) return@launch
                        refresh()
                    }
                }
            }
        }

        private suspend fun refresh() {
            when (val result = client.getDeviceSessions()) {
                is Outcome.Ok -> {
                    dispatch(
                        state()
                            .copy(
                                sessions = result.value.ordered(),
                                error = state().error.takeUnless { refreshFailed },
                                tokenFailure = state().tokenFailure.takeUnless { refreshFailed },
                            )
                    )
                    refreshFailed = false
                }
                is Outcome.Err -> failure(result, fromRefresh = true)
            }
        }

        private fun failure(result: Outcome.Err, fromRefresh: Boolean = false) {
            refreshFailed = fromRefresh
            val tokenFailure = result.telegramError.loginTokenFailure()
            dispatch(
                state()
                    .copy(
                        error = result.telegramError.takeIf { tokenFailure == null },
                        tokenFailure = tokenFailure,
                    )
            )
        }
    }
}

private fun List<DeviceSession>.ordered() =
    sortedWith(compareByDescending<DeviceSession> { it.current }.thenByDescending { it.dateActive })
