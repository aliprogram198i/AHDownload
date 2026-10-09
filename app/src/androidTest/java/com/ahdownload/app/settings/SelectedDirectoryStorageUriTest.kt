package com.ahdownload.app.settings

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectedDirectoryStorageUriTest {

    @Test
    fun parentUriTargetsSelectedRootDocumentRatherThanTreeGrantUri() {
        val treeUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        )
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)

        val parentUri = SelectedDirectoryStorage.buildParentDocumentUri(treeUri, parentId)

        assertNotEquals(treeUri, parentUri)
        assertEquals(treeUri.authority, parentUri.authority)
        assertEquals(
            treeUri.pathSegments + listOf("document", parentId),
            parentUri.pathSegments,
        )
    }

    @Test
    fun parentUriPreservesNestedUnicodeDirectoryDocumentId() {
        val treeUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADownload%2F%D9%84%D8%A8%D9%8A%D9%8A%D9%84%D8%A9%D8%A7",
        )
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)

        assertTrue("Expected a nested directory ID", parentId.contains('/'))
        assertTrue("Expected a Unicode directory name", parentId.any { it.code > 127 })

        val parentUri = SelectedDirectoryStorage.buildParentDocumentUri(treeUri, parentId)

        assertEquals(
            treeUri.pathSegments + listOf("document", parentId),
            parentUri.pathSegments,
        )
    }

    @Test
    fun rejectsUriThatIsNotATreeGrant() {
        val invalidUri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADownload",
        )

        try {
            SelectedDirectoryStorage.buildParentDocumentUri(invalidUri, "primary:Download")
            throw AssertionError("Expected invalid tree URI to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: createDocument must never receive a non-tree URI as the tree grant.
        }
    }
}
