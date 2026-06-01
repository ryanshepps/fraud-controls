package com.fraudcontrols.api

import kotlinx.serialization.Serializable

@Serializable
internal data class ApiErrorResponse(
    val error: String,
)
