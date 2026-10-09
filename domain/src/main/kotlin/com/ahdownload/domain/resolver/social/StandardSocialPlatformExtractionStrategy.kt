package com.ahdownload.domain.resolver.social

import com.ahdownload.domain.model.MediaPlatform

/** Baseline policy for platforms using shared browser observation and the common HTML parser. */
internal open class StandardSocialPlatformExtractionStrategy(
    final override val platform: MediaPlatform,
) : SocialPlatformExtractionStrategy
