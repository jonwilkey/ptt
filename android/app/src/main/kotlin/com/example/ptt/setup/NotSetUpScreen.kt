package com.example.ptt.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.ptt.R

/** Placeholder until enrollment exists. */
@Composable
fun NotSetUpScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.not_set_up_yet), style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
