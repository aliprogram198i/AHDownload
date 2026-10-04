package com.ahdownload.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryScreensRenderAndProduceEvidence() {
        composeRule.onNodeWithText("AHDownload").fetchSemanticsNode()
        capture("home")

        composeRule.onNodeWithText("التنزيلات").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("التنزيلات").fetchSemanticsNode()
        capture("downloads")

        composeRule.onNodeWithText("الاستوديو").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Smart Studio").fetchSemanticsNode()
        capture("studio")

        composeRule.onNodeWithText("الإعدادات").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("إعدادات AHDownload").fetchSemanticsNode()
        capture("settings")

        composeRule.onNodeWithText("سجل التطبيق").fetchSemanticsNode()
    }

    private fun capture(name: String) {
        composeRule.runOnIdle {
            val view = composeRule.activity.window.decorView
            require(view.width > 0 && view.height > 0) {
                "VISUAL_CAPTURE_EMPTY_VIEW"
            }
            val bitmap = Bitmap.createBitmap(
                view.width,
                view.height,
                Bitmap.Config.ARGB_8888
            )
            try {
                view.draw(Canvas(bitmap))
                val directory = composeRule.activity.getDir(
                    "visual-audit",
                    Context.MODE_PRIVATE
                )
                val outputFile = File(directory, "$name.png")
                FileOutputStream(outputFile).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "VISUAL_CAPTURE_WRITE_FAILED"
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
