package io.github.surioustype.localscribe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.surioustype.localscribe.ui.LocalScribeApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var inboundAudio by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LocalScribeApplication
        lifecycleScope.launch {
            // Recovery must finish before UI can expose jobs or service actions.
            app.graph.awaitStartupRecovery()
            inboundAudio = receivedAudio(intent)
            setContent { LocalScribeApp(app.graph, inboundAudio) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inboundAudio = receivedAudio(intent)
    }

    private fun receivedAudio(intent: Intent?): String? =
        when (intent?.action) {
            Intent.ACTION_SEND ->
                IntentCompat
                    .getParcelableExtra(
                        intent,
                        Intent.EXTRA_STREAM,
                        Uri::class.java,
                    )?.toString()
            Intent.ACTION_VIEW -> intent.data?.toString()
            else -> null
        }

    fun hasMediaPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >=
                33
            ) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        return ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
