package com.masselis.tpmsadvanced.data.vehicle.interfaces

import android.content.Context
import androidx.core.content.edit
import com.masselis.tpmsadvanced.core.common.observableStateFlow
import kotlinx.coroutines.flow.MutableStateFlow

public class LogPreferences internal constructor(
    context: Context
) {

    private val sharedPreferences = context.getSharedPreferences(
        "TYRE_LOG",
        Context.MODE_PRIVATE
    )

    public val enabled: MutableStateFlow<Boolean> = observableStateFlow(
        sharedPreferences.getBoolean("ENABLED", false)
    ) { _, newValue ->
        sharedPreferences.edit { putBoolean("ENABLED", newValue) }
    }
}
