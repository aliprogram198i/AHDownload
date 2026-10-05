package com.ahdownload.domain.resolver

interface Resolver {
    suspend fun resolve(request: ResolverRequest): ResolverResult
}
