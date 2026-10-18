package org.monogram.feature.auth

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.AuthCodeType
import org.monogram.core.models.AuthSession
import org.monogram.core.models.AuthState
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.session.SessionOps

@OptIn(ExperimentalCoroutinesApi::class)
class AuthStoreTest {
    @Test
    fun restoresEmailDeliveryWithoutRequestingAnotherCode() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val challenge = challenge().copy(codeType = AuthCodeType.Email, emailPattern = "a***@example.com")
        val client = Client(restore = challenge)
        val store = AuthStoreFactory(DefaultStoreFactory(), client) { 1_000 }.create()
        try {
            runCurrent()
            assertEquals(AuthStore.Phase.CodeEntry(challenge), store.state.phase)
            assertEquals(0, client.sends)
            assertFalse(store.state.loading)
        } finally {
            store.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun resendWaitsForDeadlinePreventsOverlapAndReplacesHash() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = Client(restore = challenge())
        val store = AuthStoreFactory(DefaultStoreFactory(), client) { 1_000 + currentTime / 1_000 }.create()
        try {
            runCurrent()
            store.accept(AuthStore.Intent.ResendCode)
            runCurrent()
            assertEquals(0, client.resends)
            assertEquals(60, store.state.retrySeconds.toInt())
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(store.state.canResend)
            store.accept(AuthStore.Intent.CodeChanged("12345"))
            store.accept(AuthStore.Intent.ResendCode)
            store.accept(AuthStore.Intent.ResendCode)
            runCurrent()
            assertEquals(1, client.resends)
            client.resent.complete(Outcome.Ok(challenge().copy(phoneCodeHash = "replacement", nextType = null)))
            runCurrent()
            assertEquals("replacement", store.state.challenge?.phoneCodeHash)
            assertEquals("", store.state.code)
            assertFalse(store.state.canResend)
        } finally {
            store.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun emailSetupContinuesToTheReturnedStep() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val setup = challenge().copy(codeType = AuthCodeType.EmailSetup)
        val client = Client(restore = setup)
        val store = AuthStoreFactory(DefaultStoreFactory(), client) { 1_000 }.create()
        try {
            runCurrent()
            assertTrue(store.state.phase is AuthStore.Phase.EmailEntry)
            store.accept(AuthStore.Intent.EmailChanged(" person@example.com "))
            store.accept(AuthStore.Intent.SendEmail)
            runCurrent()
            assertEquals("person@example.com", client.email)
            assertEquals(AuthCodeType.EmailSetupCode, store.state.challenge?.codeType)
            store.accept(AuthStore.Intent.CodeChanged("123456"))
            store.accept(AuthStore.Intent.SignIn)
            runCurrent()
            assertEquals(AuthStore.Phase.PasswordEntry, store.state.phase)
            assertEquals("", store.state.code)
        } finally {
            store.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun sendCodeCanFinishAuthorizationOrRequestOnlyThePassword() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (result in listOf(
                AuthState.AwaitingPassword("+12025550123"),
                AuthState.Authorized(AuthSession(PeerId(42), 2)),
            )) {
                val client = Client(send = result)
                val store = AuthStoreFactory(DefaultStoreFactory(), client) { 1_000 }.create()
                try {
                    runCurrent()
                    store.accept(AuthStore.Intent.PhoneChanged("+12025550123"))
                    store.accept(AuthStore.Intent.SendCode)
                    store.accept(AuthStore.Intent.SendCode)
                    runCurrent()
                    assertEquals(1, client.sends)
                    assertEquals(
                        if (result is AuthState.Authorized) AuthStore.Phase.Authorized(result.session) else AuthStore.Phase.PasswordEntry,
                        store.state.phase,
                    )
                } finally {
                    store.dispose()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun emailResendWorksWithoutANextPhoneDeliveryType() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = Client(restore = challenge().copy(
            codeType = AuthCodeType.Email, nextType = null, resendAvailableAt = 0,
        ))
        val store = AuthStoreFactory(DefaultStoreFactory(), client) { 1_000 }.create()
        try {
            runCurrent()
            assertTrue(store.state.canResend)
            store.accept(AuthStore.Intent.ResendCode)
            runCurrent()
            assertEquals(1, client.resends)
            client.resent.complete(Outcome.Ok(challenge().copy(codeType = AuthCodeType.Email)))
            runCurrent()
        } finally {
            store.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pendingEmailResetCanBeCompletedWhenItsDeadlinePasses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = Client(restore = challenge().copy(
            codeType = AuthCodeType.Email, emailResetAvailableAt = 1_000, emailResetPendingDate = 1_060,
        ))
        val store = AuthStoreFactory(DefaultStoreFactory(), client) { 1_000 + currentTime / 1_000 }.create()
        try {
            runCurrent()
            store.accept(AuthStore.Intent.ResetEmail)
            runCurrent()
            assertEquals(0, client.resets)
            assertFalse(store.state.canResetEmail)
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(store.state.canResetEmail)
            store.accept(AuthStore.Intent.ResetEmail)
            runCurrent()
            assertEquals(1, client.resets)
            assertTrue(store.state.phase is AuthStore.Phase.EmailEntry)
        } finally {
            store.dispose()
            Dispatchers.resetMain()
        }
    }

    private fun challenge() = AuthState.AwaitingCode(
        phone = "+12025550123", phoneCodeHash = "initial", nextType = AuthCodeType.Sms,
        resendAvailableAt = 1_060,
    )

    private class Client(
        private val restore: AuthState = AuthState.LoggedOut,
        private val send: AuthState = AuthState.LoggedOut,
    ) : SessionOps {
        var sends = 0
        var resends = 0
        var resets = 0
        var email: String? = null
        val resent = CompletableDeferred<Outcome<AuthState>>()
        override suspend fun restoreAuth() = Outcome.Ok(restore)
        override suspend fun connect() = Outcome.Ok(Unit)
        override suspend fun logout() = Outcome.Ok(Unit)
        override fun close() = Unit
        override fun libraryVersion() = "test"
        override suspend fun sendAuthCode(phone: String): Outcome<AuthState> {
            sends++
            return Outcome.Ok(send)
        }
        override suspend fun resendAuthCode(phone: String, phoneCodeHash: String): Outcome<AuthState> {
            resends++
            return resent.await()
        }
        override suspend fun sendAuthEmail(phone: String, phoneCodeHash: String, email: String): Outcome<AuthState> {
            this.email = email
            return Outcome.Ok(AuthState.AwaitingCode(phone, phoneCodeHash, AuthCodeType.EmailSetupCode, 6))
        }
        override suspend fun resetAuthEmail(phone: String, phoneCodeHash: String): Outcome<AuthState> {
            resets++
            return Outcome.Ok(AuthState.AwaitingCode(phone, phoneCodeHash, AuthCodeType.EmailSetup))
        }
        override suspend fun signIn(phone: String, phoneCodeHash: String, code: String) = Outcome.Ok(AuthState.AwaitingPassword(phone))
        override suspend fun checkPassword(password: String): Outcome<AuthState.Authorized> = error("not expected")
    }
}
