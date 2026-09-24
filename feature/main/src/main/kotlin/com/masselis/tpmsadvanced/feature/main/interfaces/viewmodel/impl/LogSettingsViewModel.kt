package com.masselis.tpmsadvanced.feature.main.interfaces.viewmodel.impl

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masselis.tpmsadvanced.data.vehicle.interfaces.LogPreferences
import com.masselis.tpmsadvanced.data.vehicle.interfaces.TyreLogDatabase
import com.masselis.tpmsadvanced.data.vehicle.interfaces.toCsv
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

public class LogSettingsViewModel internal constructor(
    logPreferences: LogPreferences,
    private val tyreLogDatabase: TyreLogDatabase,
    private val context: Context,
) : ViewModel() {
    public val enabled: MutableStateFlow<Boolean> = logPreferences.enabled

    /**
     * Writes the tyre log to a CSV file in the app's cache and returns a content [Uri] suitable
     * for sharing (e.g. via `Intent.ACTION_SEND`), or null if there's nothing to export.
     */
    public fun exportCsv(onExported: (Uri) -> Unit) {
        viewModelScope.launch {
            val entries = tyreLogDatabase.selectAll()
            if (entries.isEmpty()) return@launch
            val uri = withContext(IO) {
                val file = File(context.cacheDir, "tyre_log.csv")
                file.writeText(entries.toCsv())
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }
            onExported(uri)
        }
    }
}
