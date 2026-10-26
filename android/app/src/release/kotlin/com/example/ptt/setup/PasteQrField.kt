package com.example.ptt.setup

import androidx.compose.runtime.Composable

/** Release builds have no paste-QR field: a QR must come from the scanner. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun PasteQrField(enabled: Boolean, onSubmit: (String) -> Unit) = Unit
