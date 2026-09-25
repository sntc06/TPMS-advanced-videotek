package com.masselis.tpmsadvanced.feature.main.interfaces.composable

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masselis.tpmsadvanced.feature.main.interfaces.viewmodel.impl.LogSettingsViewModel
import com.masselis.tpmsadvanced.feature.main.ioc.Bindings.Companion.LogSettingsViewModel

@Composable
public fun LogSettings(
    modifier: Modifier = Modifier,
    viewModel: LogSettingsViewModel = viewModel { LogSettingsViewModel() }
) {
    val enabled by viewModel.enabled.collectAsState()
    val context = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = if (enabled) "Tyre logging is enabled" else "Enable tyre logging",
                    textAlign = TextAlign.Start,
                )
                Text(
                    text = "Records pressure, temperature and battery readings to export as CSV",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { viewModel.enabled.value = it },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                viewModel.exportCsv(
                    onEmpty = {
                        Toast.makeText(context, "No tyre log data to export yet", Toast.LENGTH_LONG)
                            .show()
                    },
                ) { uri ->
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            "Export tyre log",
                        )
                    )
                }
            },
            modifier = Modifier.align(Alignment.Start),
        ) {
            Text("Export log as CSV")
        }
        OutlinedButton(
            onClick = { showClearDialog = true },
            modifier = Modifier.align(Alignment.Start),
        ) {
            Text("Clear log")
        }
    }
    if (showClearDialog) AlertDialog(
        text = {
            Text("Do you really want to delete every logged tyre reading ?\nThis action cannot be undone !")
        },
        onDismissRequest = { showClearDialog = false },
        dismissButton = {
            TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    showClearDialog = false
                    viewModel.clearLog { count ->
                        Toast
                            .makeText(context, "Deleted $count logged readings", Toast.LENGTH_LONG)
                            .show()
                    }
                },
            ) { Text("Clear log") }
        },
    )
}
