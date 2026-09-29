package io.github.surioustype.localscribe

import android.app.Application
import io.github.surioustype.localscribe.di.AppGraph
import io.github.surioustype.localscribe.service.ServiceDependenciesProvider

class LocalScribeApplication : Application(), ServiceDependenciesProvider {
    val graph: AppGraph by lazy { AppGraph(this) }
    override val transcriptionServiceDependencies get() = graph.serviceDependencies
}
