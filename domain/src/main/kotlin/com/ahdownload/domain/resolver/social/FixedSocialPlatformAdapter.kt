package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.PlatformAdapter
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider

/**
 * Shared implementation boundary for platform-specific social adapters.
 *
 * Each concrete adapter fixes one platform identity. The engine rejects requests
 * for any other platform before accessing browser or network state.
 */
abstract class FixedSocialPlatformAdapter(
    platform: MediaPlatform,
    provider: BrowserMediaSessionProvider,
    logger: DiagnosticLogger = defaultSocialDiagnosticLogger(),
    resolveTimeoutMs: Long = SOCIAL_RESOLVE_TIMEOUT_MS,
    pageClient: HttpTextClient = defaultSocialPageClient(),
) : PlatformAdapter by SocialPlatformResolverEngine(
    provider = provider,
    platform = platform,
    logger = logger,
    resolveTimeoutMs = resolveTimeoutMs,
    pageClient = pageClient,
)
