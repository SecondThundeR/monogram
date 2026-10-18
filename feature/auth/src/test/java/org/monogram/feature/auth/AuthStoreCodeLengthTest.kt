package org.monogram.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.models.AuthCodeType
import org.monogram.core.models.AuthState

class AuthStoreCodeLengthTest {
    @Test
    fun numericCodesUseTheServerLength() {
        val challenge = AuthState.AwaitingCode("+12025550123", "hash", codeLength = 6)
        assertEquals("172098", filterAuthCode("17 20984", challenge))
    }

    @Test
    fun smsWordsAndPhrasesKeepTheirLettersAndSpaces() {
        for (type in listOf(AuthCodeType.SmsWord, AuthCodeType.SmsPhrase)) {
            val challenge = AuthState.AwaitingCode("+12025550123", "hash", type, codeLength = 0)
            assertEquals("blue sky", filterAuthCode("blue sky", challenge))
        }
    }

    @Test
    fun flashCallsDoNotTruncateTheCallingNumberToAnAssumedCodeLength() {
        val challenge = AuthState.AwaitingCode("+12025550123", "hash", AuthCodeType.FlashCall, codeLength = 0)
        assertEquals("12025550123", filterAuthCode("+1 202 555 0123", challenge))
    }
}
