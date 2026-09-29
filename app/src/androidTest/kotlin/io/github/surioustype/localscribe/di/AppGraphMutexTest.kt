package io.github.surioustype.localscribe.di

import androidx.test.platform.app.InstrumentationRegistry
import io.github.surioustype.localscribe.LocalScribeApplication
import org.junit.Assert.assertSame
import org.junit.Test

class AppGraphMutexTest {
    @Test
    fun coordinatorBenchmarkAndDeletionReceiveTheApplicationMutex() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as LocalScribeApplication
        val graph = app.graph
        assertSame(graph.executionMutex, privateField(graph.coordinator, "executionMutex"))
        assertSame(graph.executionMutex, privateField(graph.benchmark, "executionMutex"))
        assertSame(graph.executionMutex, privateField(graph.modelFiles, "modelMutex"))
    }

    private fun privateField(target: Any, name: String): Any =
        target.javaClass
            .getDeclaredField(name)
            .also { it.isAccessible = true }
            .get(target) ?: error("$name was null")
}
