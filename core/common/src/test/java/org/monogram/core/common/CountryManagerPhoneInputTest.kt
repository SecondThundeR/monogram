package org.monogram.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryManagerPhoneInputTest {
    @Test
    fun guamExampleUsesTheSameDialCodeAsThePicker() {
        val country = checkNotNull(CountryManager.countryForIso("GU"))

        assertEquals("+1671 000 0000", CountryManager.exampleNumber(country))
    }

    @Test
    fun partialNumbersAreFormattedAsTheyAreTyped() {
        val country = checkNotNull(CountryManager.countryForIso("US"))

        assertEquals("+1 202", CountryManager.formatAsYouType("+1202", country))
    }

    @Test
    fun explicitInternationalNumbersAreNotInterpretedAsNationalNumbers() {
        val country = checkNotNull(CountryManager.countryForIso("BY"))

        assertEquals("DE", CountryManager.countryForTypedPhone("+491511234567", country)?.iso)
    }

    @Test
    fun moreSpecificDialCodeOverridesDefaultCountry() {
        val country = checkNotNull(CountryManager.countryForIso("US"))

        assertEquals("GU", CountryManager.countryForTypedPhone("+16715551234", country)?.iso)
    }

    @Test
    fun formattingPreservesEveryDigitIncludingLongAnonymousNumbers() {
        val country = checkNotNull(CountryManager.countryForIso("FT"))
        val number = "+888123456789012"

        assertEquals(number.filter(Char::isDigit), CountryManager.formatAsYouType(number, country).filter(Char::isDigit))
    }

    @Test
    fun barePlusIsPreservedWhileEnteringAnInternationalCode() {
        val country = checkNotNull(CountryManager.countryForIso("BY"))

        assertEquals("+", CountryManager.formatAsYouType("+", country))
    }

    @Test
    fun examplesNeverRepeatAnInternationalPrefixInTheNationalPart() {
        CountryManager.countries().forEach { country ->
            CountryManager.exampleNumber(country)?.let { example ->
                assertTrue("${country.iso}: $example", example.startsWith("+${country.code} "))
                assertTrue("${country.iso}: $example", '+' !in example.substringAfter(' '))
            }
        }
    }

    @Test
    fun invalidSimCountryFallsBackToDeviceCountry() {
        assertEquals("BY", CountryManager.resolveIso("ZZ", "BY"))
        assertEquals("BY", CountryManager.resolveIso(null, "by"))
        assertEquals("US", CountryManager.resolveIso("ZZ", "ZZ"))
    }

    @Test
    fun sharedRussianAndKazakhCodesFollowTheTypedPrefix() {
        val russia = checkNotNull(CountryManager.countryForIso("RU"))
        val kazakhstan = checkNotNull(CountryManager.countryForIso("KZ"))

        assertEquals("KZ", CountryManager.countryForTypedPhone("+77", russia)?.iso)
        assertEquals("RU", CountryManager.countryForTypedPhone("+79", kazakhstan)?.iso)
        assertEquals("KZ", CountryManager.countryForTypedPhone("+7", kazakhstan)?.iso)
    }
}
