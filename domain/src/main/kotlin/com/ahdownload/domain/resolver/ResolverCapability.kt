package com.ahdownload.domain.resolver

import com.ahdownload.domain.model.MediaKind
import com.ahdownload.domain.model.MediaPlatform

data class ResolverCapability(
    val platform: MediaPlatform,
    val supportedKinds: Set<MediaKind>,
)
