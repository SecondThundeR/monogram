package org.monogram.network.bridge.session

import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.AuthCodeType
import org.monogram.core.models.AuthSession
import org.monogram.core.models.AuthState
import org.monogram.core.models.PeerId
import uniffi.monogram_mtproto.AuthCodeTypeDto
import uniffi.monogram_mtproto.AuthStepDto

internal fun AuthStepDto.toAuthState(): AuthState = when (this) {
    AuthStepDto.LoggedOut -> AuthState.LoggedOut
    is AuthStepDto.AwaitingPassword -> AuthState.AwaitingPassword(phone)
    is AuthStepDto.Authorized -> AuthState.Authorized(AuthSession(PeerId(signed.userId), signed.dcId))
    is AuthStepDto.AwaitingCode -> AuthState.AwaitingCode(
        phone = sent.phone,
        phoneCodeHash = sent.phoneCodeHash,
        codeType = sent.codeType.toAuthCodeType(),
        codeLength = sent.codeLength,
        nextType = sent.nextType?.toAuthCodeType(),
        resendAvailableAt = sent.resendAvailableAt,
        emailPattern = sent.emailPattern,
        emailAddress = sent.emailAddress,
        emailResetAvailableAt = sent.emailResetAvailableAt,
        emailResetPendingDate = sent.emailResetPendingDate,
        fragmentUrl = sent.fragmentUrl,
        phonePattern = sent.phonePattern,
        beginning = sent.beginning,
        googleSigninAllowed = sent.googleSigninAllowed,
        appleSigninAllowed = sent.appleSigninAllowed,
    )
}

private fun AuthCodeTypeDto.toAuthCodeType(): AuthCodeType = when (this) {
    AuthCodeTypeDto.APP -> AuthCodeType.App
    AuthCodeTypeDto.SMS -> AuthCodeType.Sms
    AuthCodeTypeDto.CALL -> AuthCodeType.Call
    AuthCodeTypeDto.FLASH_CALL -> AuthCodeType.FlashCall
    AuthCodeTypeDto.MISSED_CALL -> AuthCodeType.MissedCall
    AuthCodeTypeDto.EMAIL -> AuthCodeType.Email
    AuthCodeTypeDto.EMAIL_SETUP -> AuthCodeType.EmailSetup
    AuthCodeTypeDto.EMAIL_SETUP_CODE -> AuthCodeType.EmailSetupCode
    AuthCodeTypeDto.FRAGMENT -> AuthCodeType.Fragment
    AuthCodeTypeDto.FIREBASE -> AuthCodeType.Firebase
    AuthCodeTypeDto.SMS_WORD -> AuthCodeType.SmsWord
    AuthCodeTypeDto.SMS_PHRASE -> AuthCodeType.SmsPhrase
}

fun TelegramError.requiresAuthRestart(): Boolean = requiresReauth ||
    type.startsWith("AUTH_RESTART") || type == "PHONE_CODE_EXPIRED"
