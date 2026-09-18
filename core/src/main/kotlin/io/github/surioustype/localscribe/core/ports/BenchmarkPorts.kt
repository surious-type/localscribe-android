package io.github.surioustype.localscribe.core.ports

import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.DemoSample
import io.github.surioustype.localscribe.core.model.HardwareProfile
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import kotlinx.coroutines.flow.Flow

interface BenchmarkManager {
    fun observeBenchmarks(): Flow<List<BenchmarkRecord>>

    suspend fun benchmark(
        model: InstalledModel,
        config: InferenceConfig,
        sampleId: String,
        hardwareProfile: HardwareProfile,
    ): BenchmarkRecord
}

interface DemoAudioRepository {
    fun observeSamples(): Flow<List<DemoSample>>

    suspend fun getSample(sampleId: String): DemoSample?

    /** Returns mono 16 kHz float PCM. */
    suspend fun readPcm(sampleId: String): FloatArray
}
