package com.vyora.music.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraDestructive
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextFaint
import com.vyora.music.ui.theme.VyoraTextMuted

/** The app's text field: one line, iOS-ish rounded outline, optional password masking and error line. */
@Composable
fun VyoraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = VyoraTextMuted) },
        placeholder = placeholder?.let { { Text(it, color = VyoraTextFaint) } },
        singleLine = singleLine,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            autoCorrectEnabled = false,
        ),
        isError = error != null,
        supportingText = error?.let { { Text(it, color = VyoraDestructive) } },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = VyoraText, unfocusedTextColor = VyoraText,
            cursorColor = VyoraAccent, focusedBorderColor = VyoraAccent, unfocusedBorderColor = VyoraTextFaint,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
