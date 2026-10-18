package org.monogram

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.auth.ui.PhoneInput
import org.monogram.feature.auth.ui.PhoneInputTags

class PhoneInputEditingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var phone by mutableStateOf("")
    private var imeActions = 0

    @Test
    fun clearedGuamInputShowsOnlyTheNationalPlaceholder() {
        launch()
        chooseCountry("Guam")
        field().performTextClearance()

        compose.onNodeWithText("000 0000").assertIsDisplayed()
        compose.onNodeWithText("+1 000-000-0000").assertDoesNotExist()
        field().performTextInput("5")
        compose.runOnIdle { assertEquals("+16715", phone) }
    }

    @Test
    fun selectionRangeCanBeReplaced() {
        launch("+12025550123")
        field().performTextInputSelection(TextRange(3, 6), relativeToOriginalText = true)

        assertEquals(TextRange(3, 6), field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field().performTextInput("999")
        compose.runOnIdle { assertEquals("+12999550123", phone) }
    }

    @Test
    fun changingCountryKeepsTheNationalNumber() {
        launch("+375291234567")
        chooseCountry("Germany")

        compose.runOnIdle { assertEquals("+49291234567", phone) }
    }

    @Test
    fun internationalCodeCanBeEnteredOneCharacterAtATime() {
        launch()
        field().performTextInput("+")
        compose.runOnIdle { assertEquals("+", phone) }
        field().performTextInput("4")
        field().performTextInput("9")

        compose.runOnIdle { assertEquals("+49", phone) }
    }

    @Test
    fun nextImeActionReachesTheCaller() {
        launch(imeAction = ImeAction.Next)
        field().performImeAction()

        compose.runOnIdle { assertEquals(1, imeActions) }
    }

    @Test
    fun internationalPasteKeepsAllDigits() {
        launch()
        field().performTextInput("+1 (202) 555-0123")

        compose.runOnIdle { assertEquals("+12025550123", phone) }
    }

    @Test
    fun backspaceAndForwardDeleteWorkAcrossFormattingSeparators() {
        launch("+12025550123")
        field().performTextInputSelection(TextRange(5), relativeToOriginalText = true)
        field().performKeyInput { pressKey(Key.Backspace) }
        compose.runOnIdle { assertEquals("+1205550123", phone) }
        field().performKeyInput { pressKey(Key.Delete) }
        compose.runOnIdle { assertEquals("+120550123", phone) }
    }

    @Test
    fun replacingTheWholeNumberWithNationalDigitsKeepsSelectedCountry() {
        launch()
        chooseCountry("Belarus")
        field().performTextInput("291234567")
        field().performTextInputSelection(TextRange(0, 13), relativeToOriginalText = true)
        field().performTextInput("331234567")

        compose.runOnIdle { assertEquals("+375331234567", phone) }
    }

    @Test
    fun externalValueChangesMoveTheCursorToTheNewNumber() {
        launch("+12025550123")
        field().performTextInputSelection(TextRange(2), relativeToOriginalText = true)
        compose.runOnIdle { phone = "+49" }
        field().performTextInput("1")

        compose.runOnIdle { assertEquals("+491", phone) }
    }

    private fun field() = compose.onNodeWithTag(PhoneInputTags.FIELD)

    private fun chooseCountry(name: String) {
        compose.onNodeWithTag(PhoneInputTags.COUNTRY).performClick()
        val search = compose.activity.getString(org.monogram.feature.auth.R.string.auth_country_search_hint)
        compose.onNodeWithText(search).performTextInput(name)
        compose.onNode(hasText(name) and !hasSetTextAction()).performClick()
    }

    private fun launch(initial: String = "", imeAction: ImeAction = ImeAction.Done) {
        phone = initial
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        compose.setContent {
            MonogramTheme {
                PhoneInput(
                    value = phone,
                    onValueChange = { phone = it },
                    label = "Phone number",
                    placeholder = "49 151 1234567",
                    imeAction = imeAction,
                    onImeAction = { imeActions++ },
                )
            }
        }
    }
}
