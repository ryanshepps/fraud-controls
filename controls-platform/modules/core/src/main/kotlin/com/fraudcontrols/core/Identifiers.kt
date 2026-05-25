package com.fraudcontrols.core

@JvmInline
value class EventId(val value: String) {
    init {
        require(value.isNotBlank()) { "event id must not be blank" }
    }
}

@JvmInline
value class CustomerId(val value: String) {
    init {
        require(value.isNotBlank()) { "customer id must not be blank" }
    }
}

@JvmInline
value class DeviceFingerprint(val value: String) {
    init {
        require(value.isNotBlank()) { "device fingerprint must not be blank" }
    }
}
