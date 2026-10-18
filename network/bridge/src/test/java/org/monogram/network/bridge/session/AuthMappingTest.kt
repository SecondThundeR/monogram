package org.monogram.network.bridge.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.models.AuthCodeType
import org.monogram.core.models.AuthState
import org.monogram.mtproto.MtprotoNative
import uniffi.monogram_mtproto.AuthCodeSent
import uniffi.monogram_mtproto.AuthCodeTypeDto
import uniffi.monogram_mtproto.AuthSignedIn
import uniffi.monogram_mtproto.AuthStepDto
import uniffi.monogram_mtproto.FfiConverterTypeAuthStepDto
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class AuthMappingTest {
    @Test
    fun authorizationStepsRoundTripThroughTheGeneratedWireContract() {
        for (step in listOf(
            AuthStepDto.LoggedOut,
            AuthStepDto.AwaitingCode(challenge()),
            AuthStepDto.AwaitingPassword("+12025550123"),
            AuthStepDto.Authorized(AuthSignedIn(42, 4)),
        )) {
            val buffer = ByteBuffer.allocate(FfiConverterTypeAuthStepDto.allocationSize(step).toInt())
            FfiConverterTypeAuthStepDto.write(step, buffer)
            buffer.flip()
            assertEquals(step, FfiConverterTypeAuthStepDto.read(buffer))
            assertEquals(0, buffer.remaining())
        }
    }

    @Test
    fun bridgeRetainsEmailAndRecoveryMetadata() {
        assertEquals(
            AuthState.AwaitingCode(
                phone = "+12025550123", phoneCodeHash = "new-hash", codeType = AuthCodeType.Email,
                codeLength = 6, nextType = AuthCodeType.Sms, resendAvailableAt = 1_060,
                emailPattern = "a***@example.com", emailResetAvailableAt = 1_030,
                emailResetPendingDate = 2_000, googleSigninAllowed = true,
            ),
            AuthStepDto.AwaitingCode(challenge()).toAuthState(),
        )
    }

    @Test
    fun codeAndPasswordStepsDoNotRequireAConnectionProbe() = runTest {
        for (step in listOf(AuthStepDto.AwaitingCode(challenge()), AuthStepDto.AwaitingPassword("+12025550123"))) {
            val native = object : MtprotoNative by MtprotoNative.Stub {
                override fun createClient(apiId: Int, apiHash: String, sessionPath: String) = 1L
                override fun connect(handle: Long): Unit = error("Auth must not probe the pending session")
                override fun sendAuthCode(handle: Long, phone: String) = step
                override fun destroyClient(handle: Long) = Unit
            }
            val core = SessionCore(
                TelegramCredentials(1, "test"), "build/auth-test.session", native, 20_000,
                StandardTestDispatcher(testScheduler), {},
            )
            try {
                assertEquals(Outcome.Ok(step.toAuthState()), core.sendAuthCode("+12025550123"))
            } finally {
                core.close()
            }
        }
    }

    private fun challenge() = AuthCodeSent(
        phone = "+12025550123", phoneCodeHash = "new-hash", codeType = AuthCodeTypeDto.EMAIL,
        codeLength = 6, nextType = AuthCodeTypeDto.SMS, resendAvailableAt = 1_060,
        emailPattern = "a***@example.com", emailAddress = null, emailResetAvailableAt = 1_030,
        emailResetPendingDate = 2_000, fragmentUrl = "", phonePattern = "", beginning = "",
        googleSigninAllowed = true, appleSigninAllowed = false,
    )
}
