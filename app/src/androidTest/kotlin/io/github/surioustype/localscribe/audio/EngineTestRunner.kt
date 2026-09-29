package io.github.surioustype.localscribe.audio

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Lets adapter/native tests run before the product Application is integrated. */
class EngineTestRunner : AndroidJUnitRunner() {
    override fun newApplication(classLoader: ClassLoader?, className: String?, context: Context?) =
        super.newApplication(classLoader, Application::class.java.name, context)
}
