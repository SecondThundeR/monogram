package org.monogram.feature.auth.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.monogram.core.common.Country
import org.monogram.core.common.CountryManager
import org.monogram.feature.auth.R
import java.util.Locale

object PhoneInputTags {
    const val FIELD = "phone_input_field"
    const val COUNTRY = "phone_input_country"
}

@Composable
fun PhoneInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    val context = LocalContext.current
    val defaultIso = remember(context) {
        CountryManager.resolveIso(CountryManager.simIso(context), Locale.getDefault().country)
    }
    var pickedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerVisible by rememberSaveable { mutableStateOf(false) }

    val digits = value.filter { it.isDigit() }
    val picked = CountryManager.countries().firstOrNull { it.pickerKey == pickedKey }
    val fallback = picked ?: CountryManager.countryForIso(defaultIso)
    val country = CountryManager.countryForTypedPhone(digits, fallback)
        ?: fallback
        ?: CountryManager.countries().firstOrNull()
        ?: return

    val example = remember(country.pickerKey, placeholder) {
        CountryManager.exampleNumber(country)
            ?.removePrefix("+${country.code}")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: placeholder
    }
    var editor by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    val field = if (editor.text == value) editor else TextFieldValue(value, TextRange(value.length))
    val transformation = remember(country) { PhoneNumberTransformation(country) }
    val dialCodeLabel = stringResource(R.string.auth_dial_code)
    val countryLabel = stringResource(R.string.auth_country_selected, country.name)

    OutlinedTextField(
        value = field,
        onValueChange = { input ->
            editor = normalizePhoneEdit(field, input, country)
            if (editor.text != value) onValueChange(editor.text)
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .testTag(PhoneInputTags.FIELD),
        label = { Text(label) },
        placeholder = { Text(example) },
        prefix = {
            if (value.isEmpty()) {
                Text(
                    text = "+${country.code}",
                    modifier = Modifier.semantics { contentDescription = dialCodeLabel },
                )
            }
        },
        leadingIcon = {
            Text(
                text = country.flagEmoji,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .testTag(PhoneInputTags.COUNTRY)
                    .clickable(enabled = enabled, role = Role.Button) { pickerVisible = true }
                    .semantics { contentDescription = countryLabel }
                    .padding(horizontal = 4.dp),
            )
        },
        isError = isError,
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Phone,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction() },
            onNext = { onImeAction() },
            onPrevious = { onImeAction() },
            onGo = { onImeAction() },
            onSearch = { onImeAction() },
            onSend = { onImeAction() },
        ),
        visualTransformation = transformation,
        enabled = enabled,
    )

    if (pickerVisible && enabled) {
        CountryPicker(
            selectedKey = country.pickerKey,
            onDismiss = { pickerVisible = false },
            onSelect = { target ->
                pickedKey = target.pickerKey
                pickerVisible = false
                val national = digits.takeIf { it.startsWith(country.code) }?.removePrefix(country.code).orEmpty()
                val nextValue = "+${target.code}$national"
                editor = TextFieldValue(nextValue, TextRange(nextValue.length))
                onValueChange(nextValue)
            },
        )
    }
}

@Composable
private fun CountryPicker(
    selectedKey: String,
    onDismiss: () -> Unit,
    onSelect: (Country) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember { CountryManager.countries() }
    val matches = remember(query, all) {
        val target = query.trim()
        if (target.isEmpty()) {
            all
        } else {
            val codeQuery = target.filter { it.isDigit() }
            all.filter { country ->
                country.name.contains(target, ignoreCase = true) ||
                    country.iso.contains(target, ignoreCase = true) ||
                    (codeQuery.isNotEmpty() && country.code.startsWith(codeQuery))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auth_country_picker)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.auth_country_search_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                if (matches.isNotEmpty()) {
                    HorizontalDivider()
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(matches, key = { it.pickerKey }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(item) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = item.flagEmoji, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (item.pickerKey == selectedKey) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "+${item.code}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.auth_cd_back))
            }
        },
    )
}

internal fun insertDefaultDialCode(
    edit: TextFieldValue,
    previousValue: String,
    country: Country,
): TextFieldValue {
    if (previousValue.any { it.isDigit() } ||
        edit.text.none { it.isDigit() } ||
        edit.text.trimStart().startsWith("+")
    ) {
        return edit
    }

    val digits = edit.text.filter(Char::isDigit)
    val internationalCountry = CountryManager.countryForPhone(digits)
    if (internationalCountry != null &&
        CountryManager.isValidPhoneNumber("+$digits", internationalCountry.iso) &&
        (digits.startsWith(country.code) || !CountryManager.isValidPhoneNumber("+${country.code}$digits", country.iso))
    ) {
        return edit
    }

    val prefix = "+${country.code}"
    return edit.copy(
        text = prefix + edit.text,
        selection = TextRange(edit.selection.start + prefix.length, edit.selection.end + prefix.length),
        composition = edit.composition?.let {
            TextRange(it.start + prefix.length, it.end + prefix.length)
        },
    )
}

internal fun normalizePhoneEdit(previous: TextFieldValue, input: TextFieldValue, country: Country): TextFieldValue {
    if (input.text == previous.text) return input
    val replacingAll = previous.selection.min == 0 && previous.selection.max == previous.text.length
    val edit = insertDefaultDialCode(input, if (replacingAll) "" else previous.text, country)
    val digits = edit.text.filter(Char::isDigit)
    val hasPlus = edit.text.trimStart().startsWith("+")
    val text = if (digits.isNotEmpty() || hasPlus) "+$digits" else ""
    fun offset(index: Int): Int = when {
        index == 0 -> 0
        else -> (1 + edit.text.take(index).count(Char::isDigit)).coerceAtMost(text.length)
    }
    fun range(range: TextRange) = TextRange(offset(range.start), offset(range.end))
    return TextFieldValue(text, range(edit.selection), edit.composition?.let(::range))
}

internal class PhoneNumberTransformation(private val country: Country) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = CountryManager.formatAsYouType(text.text, country)
        fun digitOffsets(value: String) = value.mapIndexedNotNull { index, char ->
            (index + 1).takeIf { char.isDigit() }
        }
        val originalDigits = digitOffsets(text.text)
        val formattedDigits = digitOffsets(formatted)
        fun offset(source: String, target: String, targetDigits: List<Int>, index: Int): Int {
            if (index == 0) return 0
            val digits = source.take(index).count(Char::isDigit)
            return if (digits > 0) targetDigits[digits - 1] else if (target.startsWith("+")) 1 else 0
        }
        return TransformedText(
            AnnotatedString(formatted),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    offset(text.text, formatted, formattedDigits, offset)

                override fun transformedToOriginal(offset: Int): Int =
                    offset(formatted, text.text, originalDigits, offset)
            },
        )
    }
}
