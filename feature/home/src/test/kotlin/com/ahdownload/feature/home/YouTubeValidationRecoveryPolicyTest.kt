package com.ahdownload.feature.home

import com.ahdownload.domain.validation.ValidationFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeValidationRecoveryPolicyTest {

    @Test
    fun refreshesOnceForContentTypeMismatchSuchAsYouTubeUmp() {
        assertTrue(ValidationFailure.ContentTypeMismatch.shouldRefreshYouTubeAfterValidation())
    }

    @Test
    fun refreshesForForbiddenMediaSource() {
        assertTrue(ValidationFailure.HttpStatus(403).shouldRefreshYouTubeAfterValidation())
    }

    @Test
    fun doesNotRefreshForUnrelatedValidationFailures() {
        assertFalse(ValidationFailure.HttpStatus(404).shouldRefreshYouTubeAfterValidation())
        assertFalse(ValidationFailure.InvalidUrl.shouldRefreshYouTubeAfterValidation())
        assertFalse(ValidationFailure.ProbeFailed.shouldRefreshYouTubeAfterValidation())
        assertFalse(ValidationFailure.HtmlResponse.shouldRefreshYouTubeAfterValidation())
    }
}
