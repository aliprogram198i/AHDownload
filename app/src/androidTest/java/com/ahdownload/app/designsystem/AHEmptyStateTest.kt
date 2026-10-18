package com.ahdownload.app.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahdownload.core.designsystem.AHEmptyState
import com.ahdownload.core.designsystem.AHTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AHEmptyStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateShowsGuidanceAndRunsItsPrimaryAction() {
        composeRule.setContent {
            AHTheme {
                var actionCount by remember { mutableStateOf(0) }
                Column(modifier = Modifier.fillMaxSize()) {
                    AHEmptyState(
                        title = "لا توجد نتائج مطابقة",
                        message = "امسح الفلاتر لعرض سجل التنزيلات كاملًا.",
                        icon = Icons.Rounded.Search,
                        actionText = "مسح عوامل التصفية",
                        onAction = { actionCount++ },
                        primaryAction = true,
                        trackingScreen = "TEST",
                        trackingId = "TEST.empty_state.clear",
                    )
                    Text("عدد الإجراءات: $actionCount")
                }
            }
        }

        composeRule.onNodeWithText("لا توجد نتائج مطابقة").assertIsDisplayed()
        composeRule.onNodeWithText("امسح الفلاتر لعرض سجل التنزيلات كاملًا.").assertIsDisplayed()
        composeRule.onNodeWithText("مسح عوامل التصفية").performClick()
        composeRule.onNodeWithText("عدد الإجراءات: 1").assertIsDisplayed()
    }
}
