# LocalScribe — Lead Implementation Mission

Ты — Lead Engineer и главный координатор разработки нового Android-приложения **LocalScribe**.

Твоя задача — спроектировать, реализовать, протестировать и подготовить к использованию полноценный Android-проект с помощью субагентов.

Не пытайся писать всё самостоятельно. Ты отвечаешь прежде всего за архитектуру, декомпозицию, интерфейсы между подсистемами, координацию субагентов, интеграцию, ревью и итоговое качество.

Используй subagent-driven development.

Для каждой существенной независимой задачи назначай отдельного implementer-субагента. После реализации задачи назначай отдельного reviewer-субагента, который проверяет соответствие спецификации и качество кода. После завершения всех задач назначь отдельного сильного reviewer-субагента для ревью всего проекта.

Параллельно запускай только действительно независимые задачи. Не позволяй нескольким агентам одновременно проектировать или изменять один и тот же интерфейс, модуль или набор файлов.

Не останавливай реализацию между задачами ради вопросов пользователю, если можешь принять разумное архитектурное решение самостоятельно. Фиксируй такие решения в документации.

Остановись и запроси пользователя только перед действительно чувствительными действиями, например созданием/передачей production signing key, публикацией первого production GitHub Release или другим необратимым/секретным действием.

---

# 1. Product

Название приложения:

**LocalScribe**

GitHub repository:

`surious-type/localscribe-android`

Android applicationId:

`io.github.surioustype.localscribe`

Основная идея:

> Local-first offline audio transcription for Android.

Приложение должно позволять пользователю брать существующие аудиозаписи с телефона и транскрибировать их полностью локально с помощью Whisper.

Никакое пользовательское аудио и никакие транскрипции не должны отправляться на сервер.

Интернет разрешён исключительно для таких функций, как:

* загрузка моделей;
* проверка обновлений приложения;
* скачивание APK обновления.

Никакой аналитики, телеметрии или облачной транскрибации не добавлять.

---

# 2. Главные технологические решения

Используй современный native Android stack.

Предпочтительная архитектура:

Kotlin
Jetpack Compose
Material 3
Coroutines / Flow
Room
Navigation Compose
Android MediaStore / Storage Access Framework
Foreground Service для продолжительной транскрибации
whisper.cpp через JNI/NDK

Используй актуальные стабильные версии Android/Jetpack/Kotlin на момент реализации. Не копируй устаревшие версии библиотек из старых примеров.

Минимальную Android API выбери разумно для современного приложения. Предпочтительно ориентироваться примерно на Android 8+ (`minSdk 26`), если технические зависимости не дают оснований выбрать другое значение.

`targetSdk` должен соответствовать актуальным требованиям Android.

Поддерживай edge-to-edge UI, светлую и тёмную тему, dynamic color как опциональную возможность.

UI должен корректно работать как минимум на обычных телефонах и Android-планшетах.

---

# 3. Основная архитектура

Подсистемы должны иметь явные интерфейсы и минимальную связанность.

Целевая архитектура приблизительно такая:

```text
MediaStore / SAF
        │
        ▼
 AudioRepository
        │
        ▼
 AudioPipeline
        │
        ├── decode
        ├── resample
        ├── mono
        ├── normalization
        └── VAD
        │
        ▼
 TranscriptionEngine
        │
        └── whisper.cpp / JNI
        │
        ▼
 TranscriptionRepository
        │
        ├── Room
        ├── Jobs
        ├── Chunks
        └── Segments
        │
        ▼
 TranscriptAssembler
        │
        ▼
 UI / Export
```

Отдельными независимыми подсистемами должны существовать:

```text
ModelManager
BenchmarkManager
RecommendationEngine

AppUpdateManager

ExportManager

TranscriptionService
```

Особенно важно:

**ModelManager и AppUpdateManager не должны зависеть друг от друга.**

Обновление APK приложения и обновление/загрузка Whisper-моделей — две полностью независимые системы.

---

# 4. Работа с аудиозаписями

Приложение должно уметь:

* показывать доступные аудиозаписи через MediaStore;
* работать с записями диктофонов;
* позволять выбрать произвольный аудиофайл через SAF;
* принимать аудио через Android Share/Open With flow, если это разумно реализуемо;
* работать с распространёнными форматами вроде M4A, MP3, WAV, AAC, OGG там, где Android codecs позволяют это сделать.

Не копируй весь исходный файл без необходимости.

Предпочитай streaming/seeking decode.

Whisper должен получать нормализованный формат:

```text
mono
16 kHz
PCM/float
```

Используй Android platform decoding там, где это возможно, вместо добавления огромной зависимости FFmpeg без необходимости.

---

# 5. Audio preprocessing

Качество транскрибации важно.

Но не применяй агрессивный denoise автоматически.

Базовый pipeline:

```text
source audio
    ↓
decode
    ↓
mono
    ↓
16 kHz resample
    ↓
safe loudness normalization
    ↓
VAD
    ↓
Whisper
```

Используй VAD, совместимый с whisper.cpp/Silero, если текущая реализация whisper.cpp это нормально поддерживает.

Архитектура должна позволять позднее подключить denoise вроде RNNoise, но не включай его по умолчанию без доказательства улучшения качества.

Если добавляешь denoise, сначала проведи A/B benchmark на тестовых записях и проверь WER/CER.

Если denoise ухудшает распознавание — не использовать его автоматически.

---

# 6. Chunking и crash-safe transcription

Это одна из наиболее важных частей приложения.

Нельзя рассматривать часовую запись как одну атомарную транскрибацию.

Реализуй транскрибацию кусками.

Начальная разумная стратегия:

```text
chunk ≈ 1–2 минуты
overlap ≈ несколько секунд
```

Конкретные значения должны быть конфигурируемыми и аргументированными.

По возможности учитывать VAD и не разрезать речь посередине слова/предложения.

Каждый chunk должен иметь persistent state в Room:

```text
id
jobId
startMs
endMs
status
attempt
modelId
raw segments
createdAt
completedAt
error
```

Состояния примерно:

```text
PENDING
PROCESSING
COMPLETED
FAILED
```

После завершения каждого chunk результат немедленно сохраняется.

Источник истины — Room, а не состояние Service или ViewModel.

Если Android:

* убил процесс;
* выгрузил приложение;
* остановил service;
* пользователь перезагрузил телефон;
* произошёл crash;

приложение должно определить последний полностью завершённый chunk и продолжить транскрибацию с ближайшего безопасного места.

Повторная транскрибация нескольких секунд допустима.

Повторная транскрибация часа — недопустима.

Обеспечь идемпотентность операций.

---

# 7. Overlap merge

Из-за overlap соседние chunks могут возвращать повторяющийся текст.

Создай отдельный:

`TranscriptAssembler`

Он должен объединять segments по timestamps и тексту.

Храни исходные Whisper segments отдельно от собранного представления.

Не уничтожай оригинальные timestamps.

Используй нормализованное suffix/prefix сопоставление как дополнительный механизм дедупликации.

Напиши хорошие unit tests на:

* точное совпадение;
* частичное совпадение;
* различия пунктуации;
* небольшие ASR-различия;
* отсутствие overlap.

---

# 8. Context между chunks

Whisper не должен полностью терять контекст между соседними chunks.

Если whisper.cpp позволяет передавать initial prompt/context, используй ограниченный контекст предыдущего chunk.

Например несколько последних предложений.

Не передавай огромную историю, поскольку это может усиливать предыдущие ошибки модели.

Поведение должно быть конфигурируемым и покрытым тестами там, где возможно.

---

# 9. whisper.cpp

Используй актуальную версию whisper.cpp и Android-compatible интеграцию.

Не копируй старый sample вслепую.

Создай абстракцию:

```text
TranscriptionEngine
```

чтобы UI, Room, background jobs и остальные слои не знали деталей whisper.cpp.

Например:

```text
interface TranscriptionEngine {
    loadModel(...)
    transcribe(...)
    unloadModel(...)
}
```

Точные API спроектируй самостоятельно.

JNI/native boundary должен быть минимальным и хорошо документированным.

Обрабатывай:

* cancellation;
* model loading errors;
* insufficient memory;
* native errors;
* corrupted model;
* процесс уничтожения.

---

# 10. Models

Модели НЕ должны находиться внутри APK.

При первом запуске пользователь получает onboarding и предложение скачать модель.

Стартовая рекомендуемая модель:

**multilingual Small**

Не English-only вариант.

Пользователь может позднее скачать другие модели.

Model Manager должен показывать:

* название;
* размер скачивания;
* размер на диске;
* языковую поддержку;
* условную оценку качества;
* benchmark этого телефона;
* статус скачивания;
* checksum verification;
* удалить модель.

Скачивание должно поддерживать:

* progress;
* cancel;
* retry;
* желательно HTTP resume;
* временный `.part` файл;
* atomic rename после checksum verification.

Никогда не считай частично скачанную модель валидной.

---

# 11. Model Repository

Model Repository архитектурно полностью отделён от обновления APK.

Создай abstraction наподобие:

```text
ModelCatalogRepository
ModelDownloadManager
InstalledModelRepository
```

Каталог моделей должен быть обновляемым независимо от APK.

Не вшивай GitHub access token или другие секреты в приложение.

Используй публичный источник моделей или публичный manifest.

Перед использованием файла модели обязательно проверяй cryptographic checksum.

---

# 12. Benchmark устройства

Не определяй рекомендуемую модель исключительно по названию SoC.

Собери hardware profile:

* RAM;
* CPU cores;
* ABI;
* Android version;
* доступную память;
* thermal state, где доступно.

Но главным источником рекомендации должен быть реальный benchmark.

После загрузки модели пользователь может запустить benchmark на фиксированной встроенной записи примерно 30–45 секунд.

Измеряй как минимум:

```text
audio duration
processing duration
real-time factor
x realtime
model
threads/config
```

При разумной возможности также:

```text
thermal state
approx memory use
```

Например:

```text
30 sec audio
8.4 sec processing

RTF = 0.28
≈ 3.6x realtime
```

Сохраняй benchmark отдельно для:

```text
device
model
model version/hash
inference configuration
```

---

# 13. Model Recommendation Engine

На основании benchmark и доступной памяти приложение может давать рекомендацию:

```text
Small — recommended
Medium — likely usable
Turbo — may be slow
```

Это именно рекомендация.

Никогда автоматически не скачивай более крупную модель.

Пользователь сам решает, какой размер и качество ему нужны.

---

# 14. Demo / Quality Test

Benchmark производительности и демонстрация качества — разные функции.

Создай встроенный demo-quality mode.

В идеале подготовить несколько коротких записей:

```text
Russian
English
Russian + English technical speech
```

Особенно полезен mixed-language пример вроде технической лекции:

```text
Сегодня рассмотрим Jetpack Compose.
Состояние компонента хранится через remember...
```

Записи должны быть намеренно неидеальными:

* естественная речь;
* лёгкий фоновый шум;
* комнатная реверберация;
* обычные паузы;
* неидеальное расстояние до микрофона.

Но речь должна оставаться понятной человеку.

Для каждого sample должен существовать reference transcript.

После теста показывай:

```text
recognized text
reference text
difference
```

Вычисляй:

```text
WER
CER
```

В обычном UI можно отображать более понятную метрику:

```text
Ошибок в словах: 4 из 61
```

В advanced details показывай WER/CER.

Очень важно:

Не добавляй в repository сомнительные copyrighted audio assets.

Используй только:

* собственные;
* public-domain;
* CC0;
* либо материалы с явно совместимой лицензией.

Для каждого bundled sample зафиксируй источник/лицензию.

Если во время реализации невозможно получить проверяемый легальный sample, реализуй всю инфраструктуру DemoAudioRepository и тестов, но не добавляй случайно найденный copyrighted audio.

---

# 15. Сравнение моделей

Если пользователь скачал несколько моделей, он должен иметь возможность прогнать одинаковый benchmark/demo и сравнить их.

Например:

```text
Model     Processing    Speed     Word errors
Small       8.4 sec      3.6x          5
Medium     19.7 sec      1.5x          2
```

Это должно помогать пользователю самостоятельно решить, стоит ли использовать более тяжёлую модель.

---

# 16. Background transcription

Продолжительная транскрибация должна выполняться foreground service.

Используй актуальный Android foreground service type для media processing в соответствии с текущей Android документацией.

Не строй всю многочасовую транскрибацию на одном бесконечном WorkManager Worker.

WorkManager допустим для вспомогательных persistent/background задач:

* восстановление очереди;
* maintenance;
* cleanup;
* retry download;
* периодическая проверка при необходимости.

Foreground notification должна показывать:

```text
имя записи
progress
processed time / total time
pause
resume
stop
```

Pause должен завершать текущую безопасную операцию или сохранять checkpoint.

Stop должен корректно изменить persistent state.

---

# 17. Thermal awareness

Whisper способен сильно нагружать телефон.

Добавь мониторинг Android thermal state там, где API позволяет.

Архитектура должна позволять снижать нагрузку при перегреве.

Например:

```text
normal → configured threads
moderate → fewer threads
severe → significantly reduce / pause
critical → pause
```

Не допускай бесконечного thermal throttling без объяснения пользователю.

Показывай понятное состояние:

```text
Транскрибация временно приостановлена:
устройство перегрелось.
```

---

# 18. Persistence model

Спроектируй Room schema как минимум вокруг сущностей:

```text
AudioSource
TranscriptionJob
TranscriptionChunk
TranscriptSegment
InstalledModel
ModelBenchmark
```

При необходимости добавляй сущности.

Продумай migrations с первого релиза.

Не полагайся на destructive migration для пользовательских транскрипций.

---

# 19. Transcript UI

После транскрибации пользователь должен видеть текст вместе с timestamps.

Пример:

```text
24:12
Сегодня мы рассмотрим производную функционала...

24:29
Для начала вспомним определение нормы...
```

При нажатии на абзац аудиоплеер должен перейти к соответствующему времени.

Добавь:

* play/pause;
* scrubber;
* текущую позицию;
* поиск по транскрипции;
* копирование текста;
* share;
* export.

Не требуется строить полноценный музыкальный проигрыватель.

Главная задача — удобно сопоставлять текст с аудио.

---

# 20. Export

Создай независимый:

`ExportManager`

Минимально поддержать:

```text
TXT
Markdown
SRT
VTT
JSON
```

Архитектура должна позволять позднее добавить DOCX/PDF.

Используй Storage Access Framework для сохранения.

Добавь Android Share Sheet.

SRT/VTT должны использовать исходные timestamps segments, а не пытаться восстанавливать время из готового текста.

---

# 21. UX/UI

Приложение должно выглядеть как современный Android-продукт, а не технический demo whisper.cpp.

Используй Material 3.

Основная navigation architecture примерно:

```text
Onboarding

Home / Recordings
    ↓
Audio Details / Start transcription
    ↓
Transcription Progress
    ↓
Transcript

Models
    ↓
Model Details
    ↓
Benchmark / Compare

Settings
    ↓
Updates
    ↓
About
```

Главный экран должен показывать:

* активную транскрибацию;
* последние записи;
* завершённые транскрипции;
* быстрый импорт файла.

Продумай empty states, loading states и errors.

Не показывай пользователю технический stack trace.

Добавь accessibility:

* content descriptions;
* разумные touch targets;
* font scaling;
* contrast;
* screen reader semantics.

Используй animations умеренно.

---

# 22. App updates via GitHub Releases

Приложение распространяется напрямую APK через GitHub Releases.

Реализуй отдельный:

`AppUpdateManager`

Он НЕ связан с ModelManager.

Для GitHub distribution build он должен использовать публичный:

`surious-type/localscribe-android`

Проверка обновлений выполняется через GitHub Releases API.

Никаких GitHub PAT внутри APK.

Должны существовать две формы проверки.

Автоматическая:

```text
при разумном событии запуска
или по ограниченному interval/cache policy
```

и ручная:

```text
Settings
→ Updates
→ Проверить обновления
```

При ручной проверке запрос должен выполняться сразу, игнорируя обычный cache interval.

UI:

```text
Версия приложения
1.4.2

Автоматически проверять обновления [ON]

Последняя проверка
Сегодня, 16:42

[ Проверить обновления ]
```

При отсутствии обновления:

```text
У вас последняя версия.
```

При наличии:

```text
Доступна версия 1.5.0

release notes

[Позже]
[Обновить]
```

---

# 23. Secure update flow

Update flow:

```text
GitHub Release
      ↓
version comparison
      ↓
APK asset
      ↓
download
      ↓
SHA-256 verification
      ↓
signing certificate verification where practical
      ↓
Android package installer
```

Android должен оставаться финальным авторитетом установки.

Не пытайся реализовать silent install.

Для GitHub flavor разрешено использовать механизм установки APK из неизвестного источника с системным подтверждением Android.

Если необходим `REQUEST_INSTALL_PACKAGES`, он должен присутствовать только в GitHub distribution flavor.

---

# 24. Product flavors

Заложи как минимум архитектурное разделение:

```text
githubRelease
playRelease
```

`githubRelease`:

* GitHub updater;
* APK distribution;
* возможность REQUEST_INSTALL_PACKAGES.

`playRelease`:

* не должен self-update APK;
* не должен требовать REQUEST_INSTALL_PACKAGES;
* пригоден для будущей публикации через Google Play.

Даже если Play build пока не публикуется, не связывай core application logic с GitHub updater.

---

# 25. CI

Настрой GitHub Actions.

Для Pull Request:

```text
format/lint
static analysis
unit tests
Android lint
assembleDebug
```

Для push в main:

то же самое + downloadable debug APK artifact.

Используй Gradle cache.

CI должен быть воспроизводимым.

---

# 26. CD / Releases

При создании tag:

```text
vX.Y.Z
```

workflow должен:

```text
checkout
validate version
tests
lint
build release
sign APK
calculate SHA-256
create GitHub Release
attach APK
attach checksum
attach release notes
```

Название asset желательно стабильное и machine-readable, например:

```text
localscribe-v1.2.0.apk
localscribe-v1.2.0.sha256
```

Production signing key НИКОГДА не коммитить.

Подготовь workflow для GitHub Actions secrets примерно уровня:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Но перед созданием настоящего production signing key / секретов остановись и попроси пользователя выполнить/подтвердить security-sensitive часть.

Debug CI должен работать без production signing secrets.

---

# 27. Repository

Создай новый repository:

`surious-type/localscribe-android`

Если есть возможность выбрать visibility, по умолчанию используй **public**, поскольку приложение должно получать GitHub Releases без встроенного GitHub token.

Не используй существующий `app-android`.

Начни чистый проект.

Структурируй repository профессионально.

Минимум:

```text
README.md
docs/
.github/workflows/
app/
```

Добавь:

```text
docs/architecture.md
docs/model-system.md
docs/transcription-pipeline.md
docs/update-system.md
```

README должен объяснять:

* что такое LocalScribe;
* local-first/privacy approach;
* как собрать;
* как скачать debug APK;
* как устроены модели;
* как сделать release;
* где хранятся signing secrets.

---

# 28. Testing

TDD предпочтителен для domain logic.

Обязательно unit-test:

```text
ChunkPlanner
TranscriptAssembler
overlap deduplication
WER
CER
version comparison
update parsing
model recommendation
benchmark calculations
state transitions
export formatting
```

Room:

* DAO tests;
* migration tests.

UI:

покрой основные critical flows Compose tests там, где это разумно.

Native boundary:

добавь минимальные integration/instrumentation tests, которые подтверждают загрузку test model и корректную обработку короткого аудио, если размер fixtures позволяет это сделать в CI.

Не заставляй CI скачивать гигабайтные модели ради каждого unit test.

---

# 29. Error handling

Продумай ошибки заранее.

Например:

```text
нет разрешения на audio
файл удалён
файл недоступен
unsupported codec
нет места
model checksum mismatch
model corrupted
insufficient memory
native crash/error
background service stopped
download interrupted
GitHub unavailable
APK verification failed
```

Ошибки должны быть domain errors, а не случайные исключения, протекающие напрямую в UI.

Пользователь должен получать actionable сообщение.

---

# 30. Privacy

Основная продуктовая ценность — local-first.

Никаких uploads.

Никакой telemetry.

Никакой analytics SDK.

Никаких crash-reporting SDK, которые автоматически отправляют пользовательские данные наружу.

Не логируй содержимое транскрипций целиком в release builds.

Не логируй пути/имена файлов без необходимости.

---

# 31. Performance

Не держи целый многочасовой decoded PCM в RAM.

Используй streaming/chunk processing.

Модель должна загружаться осмысленно и переиспользоваться между chunks одной job.

Не загружай модель заново для каждого двухминутного chunk.

Следи за:

```text
RAM
native allocations
threads
thermal throttling
battery
```

---

# 32. Suggested decomposition for subagents

Это не жёсткий список задач. Сначала создай dependency-aware implementation plan.

Пример доменов:

```text
A. Android project/bootstrap + architecture
B. Room/domain model
C. MediaStore/SAF + audio decoder
D. whisper.cpp JNI integration
E. chunking/checkpoint/resume pipeline
F. transcript assembler
G. Model Manager
H. benchmark/recommendation
I. background service/thermal management
J. Compose UI
K. transcript player/export
L. GitHub updater
M. GitHub Actions CI/CD
N. integration/e2e tests
```

Параллельно разрешается, например, делать UI prototypes и Model Catalog только после стабилизации их domain interfaces.

Нельзя параллельно поручать двум агентам разные реализации одного `TranscriptionRepository`.

---

# 33. Subagent workflow

Для каждой задачи:

1. Lead фиксирует requirements и interfaces.
2. Создаёт узкий self-contained brief.
3. Отправляет fresh implementer agent.
4. Implementer реализует только свой scope.
5. Implementer запускает необходимые tests.
6. Lead получает diff/result.
7. Отдельный reviewer agent проверяет:

   * соответствие spec;
   * correctness;
   * architecture;
   * Android lifecycle;
   * concurrency;
   * error handling;
   * tests.
8. Findings возвращаются implementer для исправления.
9. После clean review задача считается завершённой.
10. Lead продолжает dependency graph.

Не используй одного огромного субагента на весь проект.

---

# 34. Final review

После завершения всех feature tasks назначь нового сильного агента, который ранее не реализовывал проект.

Он должен провести broad review:

```text
architecture
correctness
process-death handling
threading/concurrency
JNI/native lifecycle
memory
security
privacy
Android permissions
background execution
update security
Room migrations
UI state
accessibility
test coverage
CI/CD
```

Исправь значимые findings.

После этого запусти полный набор:

```text
unit tests
lint
static analysis
assembleDebug
release build where secrets are not required
instrumentation tests where available
```

Нельзя утверждать, что проект готов, пока эти проверки реально не выполнены.

---

# 35. Definition of Done

Первая полноценная версия считается реализованной, когда пользователь может:

1. установить APK;
2. открыть LocalScribe;
3. увидеть onboarding;
4. скачать Small model;
5. проверить checksum;
6. запустить benchmark;
7. выбрать запись диктофона или аудиофайл;
8. начать транскрибацию;
9. свернуть приложение;
10. видеть foreground notification;
11. пережить process death и продолжить с checkpoint;
12. открыть готовую транскрипцию;
13. нажать на segment и перейти к соответствующему месту аудио;
14. искать по тексту;
15. экспортировать TXT/MD/SRT/VTT/JSON;
16. открыть Models;
17. скачать другую модель;
18. сравнить benchmark моделей;
19. запустить встроенный quality demo;
20. открыть Settings → Updates;
21. нажать «Проверить обновления»;
22. увидеть результат проверки GitHub Release;
23. скачать проверенный APK обновления;
24. передать его системному Android installer.

---

# 36. Что сделать сначала

Начни НЕ с написания случайного кода.

Сначала:

1. проверь доступное окружение;
2. проверь GitHub authentication;
3. создай/подготовь `surious-type/localscribe-android`;
4. создай architecture/spec documents;
5. сформируй dependency-aware implementation plan;
6. выдели интерфейсы между крупными подсистемами;
7. определи задачи, которые безопасно выполнять параллельно;
8. затем запускай subagents и начинай реализацию.

Не спрашивай пользователя подтверждать уже утверждённые продуктовые решения из этого prompt.

---

# 37. Reporting

Веди progress ledger внутри repository/workspace, чтобы после compaction или перезапуска Lead мог понять:

```text
что завершено
что в работе
какой commit относится к задаче
какие решения были приняты
какие review findings остались
```

Не полагайся только на conversation context.

После завершения дай пользователю конкретный отчёт:

```text
что реализовано
что протестировано
какие APK доступны
какие ограничения остались
что требует пользовательского действия
```

В частности явно выдели шаги, необходимые для production signing key и первого release.

Главный приоритет: не скорость написания максимального количества кода, а рабочий, устойчивый и поддерживаемый LocalScribe, который безопасно транскрибирует длинные аудиозаписи локально и не теряет часы вычислений при остановке процесса.
