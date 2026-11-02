package com.example.ptt.talk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ptt.R
import com.example.ptt.net.DebugImpairment

/** Debug builds: the impairment in force and the last RTT sample, for the clip-mode device test. */
@Composable
fun DebugReadout(rttMillis: Long?, timedOut: Boolean) {
    val impairment by DebugImpairment.state.collectAsState()
    val style = MaterialTheme.typography.bodySmall
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            if (impairment.active) {
                stringResource(R.string.debug_impairment, impairment.delayMs, impairment.lossPct)
            } else {
                stringResource(R.string.debug_impairment_off)
            },
            style = style
        )
        Text(
            when {
                timedOut -> stringResource(R.string.debug_rtt_timed_out)
                rttMillis != null -> stringResource(R.string.debug_rtt, rttMillis)
                else -> stringResource(R.string.debug_rtt_none)
            },
            style = style
        )
    }
}
