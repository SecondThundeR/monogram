package org.monogram.core.models

sealed interface QrLogin {
    class Code(val link: String, val expires: Int) : QrLogin

    data class Authorized(val session: AuthSession) : QrLogin

    data object AwaitingPassword : QrLogin
}
