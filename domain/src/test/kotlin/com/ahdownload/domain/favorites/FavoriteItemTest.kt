package com.ahdownload.domain.favorites

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FavoriteItemTest {
    @Test
    fun favoriteKeyNormalizesUrl() {
        assertEquals(
            "https://www.youtube.com/watch/?v=abc",
            FavoriteKey.fromUrl(" HTTPS://www.YouTube.com/watch/?v=abc#fragment "),
        )
    }

    @Test
    fun invalidFavoriteUrlRejected() {
        assertFailsWith<IllegalArgumentException> {
            FavoriteItem(
                id = "1",
                url = "file:///tmp/a.mp4",
                title = null,
                thumbnailUrl = null,
                createdAtEpochMs = 1L,
            )
        }
    }
}
