package com.ahdownload.domain.resolver

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink

data class ResolverRequest(
    val link: MediaLink,
    val requestedKind: MediaKind? = null,
    /** Stable correlation id for one resolve operation; safe to expose in diagnostics. */
    val operationId: String? = null,
)
