package io.github.surioustype.localscribe.updates

import android.content.Context
import io.github.surioustype.localscribe.core.ports.AppUpdateManager
import io.github.surioustype.localscribe.network.HttpsNetworkClient
import kotlinx.coroutines.CoroutineScope
import java.io.File

fun createAppUpdateManager(
    context: Context,
    scope: CoroutineScope,
    automaticChecksEnabled: () -> Boolean,
): AppUpdateManager =
    GithubAppUpdateManager(
        networkClient = HttpsNetworkClient(),
        checkStore = SharedPreferencesUpdateCheckStore(context),
        platform = AndroidUpdatePlatform(context),
        updateDirectory = File(context.cacheDir, "updates"),
        scope = scope,
        nowEpochMs = System::currentTimeMillis,
        automaticChecksEnabled = automaticChecksEnabled,
    )
