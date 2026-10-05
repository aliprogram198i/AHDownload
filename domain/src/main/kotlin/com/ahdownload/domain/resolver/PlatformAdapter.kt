package com.ahdownload.domain.resolver

import com.ahdownload.domain.model.MediaPlatform

interface PlatformAdapter {
    val capability: ResolverCapability

    suspend fun resolve(request: ResolverRequest): ResolverResult
}
