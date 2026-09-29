package io.github.surioustype.localscribe.updates

import android.content.Context
import io.github.surioustype.localscribe.core.ports.AppUpdateManager
import kotlinx.coroutines.CoroutineScope

@Suppress("UNUSED_PARAMETER")
fun createAppUpdateManager(
    context: Context,
    scope: CoroutineScope,
    automaticChecksEnabled: () -> Boolean,
): AppUpdateManager = PlayAppUpdateManager()
