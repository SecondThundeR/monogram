package org.monogram.feature.auth.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Country
import org.monogram.core.common.CountryManager

class PhoneInputTest {
    private val country = Country(name = "Belarus", code = "375", iso = "BY", flagEmoji = "🇧🇾")

    @Test
    fun firstDigitIncludesDisplayedCountryCode() {
        val edit = insertDefaultDialCode(TextFieldValue("2", TextRange(1)), "", country)

        assertEquals("+3752", edit.text)
        assertEquals(TextRange(5), edit.selection)
    }

    @Test
    fun firstDigitMatchingCountryCodeStillBelongsToNationalNumber() {
        val country = Country(name = "United States", code = "1", iso = "US", flagEmoji = "🇺🇸")
        val edit = insertDefaultDialCode(TextFieldValue("1", TextRange(1)), "", country)

        assertEquals("+11", edit.text)
        assertEquals(TextRange(3), edit.selection)
    }

    @Test
    fun nationalNumberPasteIncludesDisplayedCountryCode() {
        val edit = insertDefaultDialCode(TextFieldValue("29 123 45 67", TextRange(12)), "", country)

        assertEquals("+37529 123 45 67", edit.text)
        assertEquals(TextRange(16), edit.selection)
    }

    @Test
    fun explicitInternationalNumberKeepsItsCountryCode() {
        val input = TextFieldValue("+49 151 1234567", TextRange(14))

        assertEquals(input, insertDefaultDialCode(input, "", country))
    }

    @Test
    fun laterEditsDoNotInsertCountryCodeAgain() {
        val input = TextFieldValue("+37529", TextRange(6))

        assertEquals(input, insertDefaultDialCode(input, "+3752", country))
    }

    @Test
    fun clearingInputLeavesItEmpty() {
        val input = TextFieldValue("")

        assertEquals(input, insertDefaultDialCode(input, "+3752", country))
        assertEquals(input, insertDefaultDialCode(input, "", country))
    }

    @Test
    fun selectionOnlyEditPreservesTheEntireRange() {
        val previous = TextFieldValue("+375291234567", TextRange(12))
        val input = previous.copy(selection = TextRange(9, 4))

        assertEquals(input, normalizePhoneEdit(previous, input, country))
    }

    @Test
    fun internationalPasteRemovesSeparatorsAndMapsSelection() {
        val input = TextFieldValue("+1 (202) 555-0123", TextRange(17))
        val edit = normalizePhoneEdit(TextFieldValue(""), input, country)

        assertEquals("+12025550123", edit.text)
        assertEquals(TextRange(12), edit.selection)
    }

    @Test
    fun replacingAllWithANationalNumberIncludesCountryCode() {
        val previous = TextFieldValue("+375291234567", TextRange(0, 13))
        val input = TextFieldValue("291234567", TextRange(9))
        val edit = normalizePhoneEdit(previous, input, country)

        assertEquals("+375291234567", edit.text)
        assertEquals(TextRange(13), edit.selection)
    }

    @Test
    fun completeNumberPastedWithoutPlusDoesNotRepeatCountryCode() {
        val input = TextFieldValue("375291234567", TextRange(12))
        val edit = normalizePhoneEdit(TextFieldValue(""), input, country)

        assertEquals("+375291234567", edit.text)
        assertEquals(TextRange(13), edit.selection)
    }

    @Test
    fun internationalPasteWithoutPlusCanOverrideDefaultCountry() {
        val input = TextFieldValue("1 202 555 0123", TextRange(14))
        val edit = normalizePhoneEdit(TextFieldValue(""), input, country)

        assertEquals("+12025550123", edit.text)
        assertEquals(TextRange(12), edit.selection)
    }

    @Test
    fun validNationalPasteTakesPriorityOverAnOverlappingInternationalCode() {
        val country = checkNotNull(CountryManager.countryForIso("US"))
        val input = TextFieldValue("2025550123", TextRange(10))
        val edit = normalizePhoneEdit(TextFieldValue(""), input, country)

        assertEquals("+12025550123", edit.text)
        assertEquals(TextRange(12), edit.selection)
    }

    @Test
    fun compositionRangeTracksInsertedCountryCode() {
        val input = TextFieldValue("29", TextRange(2), TextRange(0, 2))
        val edit = normalizePhoneEdit(TextFieldValue(""), input, country)

        assertEquals("+37529", edit.text)
        assertEquals(TextRange(6), edit.selection)
        assertEquals(TextRange(4, 6), edit.composition)
    }

    @Test
    fun deletingBackToPlusDoesNotRestoreCountryCode() {
        val edit = normalizePhoneEdit(TextFieldValue("+3", TextRange(2)), TextFieldValue("+", TextRange(1)), country)

        assertEquals("+", edit.text)
        assertEquals(TextRange(1), edit.selection)
    }

    @Test
    fun formattingMapsEveryCursorPositionAndPreservesDigitsForEveryCountry() {
        CountryManager.countries().forEach { country ->
            val transformation = PhoneNumberTransformation(country)
            val samples = listOf("", "+", "+${country.code}") +
                listOf("2", "202", "2025550123", "1234567890123456").map { "+${country.code}$it" }
            samples.forEach { phone ->
                val transformed = transformation.filter(AnnotatedString(phone))
                val formatted = transformed.text.text
                assertEquals(country.pickerKey, phone.filter(Char::isDigit), formatted.filter(Char::isDigit))
                (0..phone.length).forEach { index ->
                    val offset = transformed.offsetMapping.originalToTransformed(index)
                    assertTrue(country.pickerKey, offset in 0..formatted.length)
                    assertEquals(country.pickerKey, index, transformed.offsetMapping.transformedToOriginal(offset))
                }
                (0..formatted.length).forEach { index ->
                    assertTrue(country.pickerKey, transformed.offsetMapping.transformedToOriginal(index) in 0..phone.length)
                }
            }
        }
    }
}
