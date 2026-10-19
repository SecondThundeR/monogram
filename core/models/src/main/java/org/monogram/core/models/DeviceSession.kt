package org.monogram.core.models

data class DeviceSession(
    val hash: Long,
    val current: Boolean,
    val passwordPending: Boolean,
    val deviceModel: String,
    val platform: String,
    val systemVersion: String,
    val appName: String,
    val appVersion: String,
    val dateActive: Int,
    val ip: String,
    val country: String,
    val region: String,
)
