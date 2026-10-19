package org.monogram.network.bridge.session

import kotlin.io.encoding.Base64
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.DeviceSession
import uniffi.monogram_mtproto.DeviceSessionDto

enum class LoginTokenFailure {
    Invalid,
    Expired,
    AlreadyAccepted,
    AlreadyLoggedIn,
}

fun TelegramError.loginTokenFailure(): LoginTokenFailure? =
    when {
        type == "AUTH_TOKEN_EXPIRED" -> LoginTokenFailure.Expired
        type == "AUTH_TOKEN_ALREADY_ACCEPTED" -> LoginTokenFailure.AlreadyAccepted
        type == "AUTH_TOKEN_EXCEPTION" -> LoginTokenFailure.AlreadyLoggedIn
        type.startsWith("AUTH_TOKEN_INVALID") -> LoginTokenFailure.Invalid
        else -> null
    }

internal fun decodeLoginToken(link: String): ByteArray? {
    if (!link.startsWith("tg://login?token=") || link.length > 1024) return null
    val encoded = link.removePrefix("tg://login?token=")
    if (!encoded.matches(Regex("[A-Za-z0-9_-]+={0,2}"))) return null
    return try {
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(encoded).takeIf {
            it.isNotEmpty() && it.size <= 512
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun DeviceSessionDto.toModel() =
    DeviceSession(
        hash,
        current,
        passwordPending,
        deviceModel,
        platform,
        systemVersion,
        appName,
        appVersion,
        dateActive,
        ip,
        country,
        region,
    )
