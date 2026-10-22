package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.HardwareProfile
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.ModelDescriptor
import io.github.surioustype.localscribe.core.model.ModelKind
import io.github.surioustype.localscribe.core.model.ModelQuality
import java.util.Locale

enum class RecognitionProfile(val title: String, val explanation: String) {
    SPEED("Скорость", "Для заметок и быстрой проверки. Небольшая модель, качество может быть ниже."),
    BALANCE("Баланс", "Для обычных записей. Средний компромисс между временем и качеством."),
    ACCURACY("Точность", "Для сложной речи. Потребует больше памяти и времени."),
}

data class ModelProfileChoice(
    val model: InstalledModel?,
    val reason: String,
)

/**
 * Never claim performance without a matching on-device measurement.
 * Only installed transcription models can be selected. Memory is a warning,
 * not a promise that an OS-level allocation will succeed.
 */
fun recommendInstalledProfile(
    profile: RecognitionProfile,
    catalog: List<ModelDescriptor>,
    installed: List<InstalledModel>,
    benchmarkRecords: List<BenchmarkRecord>,
    hardware: HardwareProfile,
): ModelProfileChoice {
    val descriptions = catalog.filter { it.kind == ModelKind.TRANSCRIPTION }.associateBy { it.id }
    val candidates =
        installed.mapNotNull { model ->
            descriptions[model.descriptorId]?.let { descriptor -> model to descriptor }
        }
    if (candidates.isEmpty()) {
        return ModelProfileChoice(
            null,
            "Нет установленных моделей. Скачайте подходящую модель во вкладке «Модели».",
        )
    }
    val qualityRank = mapOf(ModelQuality.LOW to 0, ModelQuality.BALANCED to 1, ModelQuality.HIGH to 2)
    val best =
        candidates.sortedWith(
            when (profile) {
                RecognitionProfile.SPEED ->
                    compareBy<Pair<InstalledModel, ModelDescriptor>> {
                        it.second.installedBytes
                    }.thenBy { it.first.descriptorId }
                RecognitionProfile.BALANCE ->
                    compareBy<Pair<InstalledModel, ModelDescriptor>> {
                        kotlin.math.abs((qualityRank[it.second.quality] ?: 1) - 1)
                    }.thenBy { it.second.installedBytes }
                        .thenBy { it.first.descriptorId }
                RecognitionProfile.ACCURACY ->
                    compareByDescending<Pair<InstalledModel, ModelDescriptor>> {
                        qualityRank[it.second.quality] ?: 0
                    }.thenByDescending { it.second.installedBytes }
                        .thenBy { it.first.descriptorId }
            },
        ).first()
    val (model, descriptor) = best
    val measured =
        benchmarkRecords
            .filter {
                it.deviceId == hardware.deviceId &&
                    it.modelId == model.descriptorId &&
                    it.modelHash.equals(model.sha256, ignoreCase = true) &&
                    it.realTimeFactor.isFinite() &&
                    it.realTimeFactor > 0.0
            }.maxWithOrNull(
                compareBy<BenchmarkRecord> { it.createdAtEpochMs }.thenBy { it.id },
            )
    val speed =
        measured?.let {
            "Последнее измерение на устройстве: RTF " +
                String.format(Locale.ROOT, "%.2f", it.realTimeFactor) + ". "
        } ?: "Скорость на этом устройстве ещё не измерена. "
    val memoryWarning =
        if (
            descriptor.installedBytes > 0 &&
            descriptor.installedBytes > hardware.availableMemoryBytes / 2L
        ) {
            "Возможен недостаток свободной памяти. "
        } else {
            ""
        }
    return ModelProfileChoice(
        model,
        speed + memoryWarning + "Выбрана установленная " + descriptor.displayName +
            "; профиль — ориентир, а не гарантия качества.",
    )
}
