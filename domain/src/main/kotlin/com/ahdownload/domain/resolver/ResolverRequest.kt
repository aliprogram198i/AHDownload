package com.ahdownload.domain.resolver

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaLink

data class ResolverRequest(
    val link: MediaLink,
    val requestedKind: MediaKind? = null,
)
