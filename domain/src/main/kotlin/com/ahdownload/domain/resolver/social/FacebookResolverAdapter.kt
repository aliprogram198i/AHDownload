package com.ahdownload.domain.resolver.social

import com.ahdownload.core.common.DiagnosticLogger
import com.ahdownload.domain.model.MediaPlatform
import com.ahdownload.domain.resolver.HttpTextClient
import com.ahdownload.domain.resolver.browser.BrowserMediaSessionProvider

/** Dedicated resolver adapter for Facebook. */
class FacebookResolverAdapter(
    provider: BrowserMediaSessionProvider,
    logger: DiagnosticLogger = defaultSocialDiagnosticLogger(),
    resolveTimeoutMs: Long = SOCIAL_RESOLVE_TIMEOUT_MS,
    pageClient: HttpTextClient = defaultSocialPageClient(),
) : FixedSocialPlatformAdapter(
    platform = MediaPlatform.Facebook,
    provider = provider,
    logger = logger,
    resolveTimeoutMs = resolveTimeoutMs,
    pageClient = pageClient,
    strategy = FacebookSocialPlatformExtractionStrategy(),
)
