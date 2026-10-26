package com.example.ptt.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.ptt.R

/** Debug builds only (emulators have no QR to scan): enroll from pasted QR text. The text is never shown. */
@Composable
fun PasteQrField(enabled: Boolean, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.paste_qr_label)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            // Plain remember (not saveable): the text holds the enrollment secret and must not be saved.
            val submitted = text
            text = ""
            onSubmit(submitted)
        }, enabled = enabled && text.isNotBlank()) {
            Text(stringResource(R.string.paste_qr_button))
        }
    }
}
