#include <jni.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>

#include "whisper.h"

namespace {

constexpr jint native_error_out_of_memory = 1;
constexpr jint native_error_invalid_model = 2;
constexpr jint native_error_cancelled = 3;
constexpr jint native_error_failure = 4;

struct WhisperContextDeleter {
    void operator()(whisper_context * context) const {
        if (context != nullptr) {
            whisper_free(context);
        }
    }
};

using WhisperContext = std::unique_ptr<whisper_context, WhisperContextDeleter>;

struct EngineHandle {
    explicit EngineHandle(WhisperContext value) : context(std::move(value)) {}

    std::mutex lifecycle_mutex;
    WhisperContext context;
    std::atomic<bool> abort_requested{false};
};

std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<EngineHandle>> registry;
std::atomic<jlong> next_handle{1};
std::once_flag logging_once;

void disabled_log(ggml_log_level, const char *, void *) {}

void throw_native_error(JNIEnv * env, jint error_code) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    const jclass exception_class = env->FindClass(
        "io/github/surioustype/localscribe/engine/NativeBridgeException");
    if (exception_class == nullptr) {
        return;
    }
    const jmethodID constructor = env->GetMethodID(exception_class, "<init>", "(I)V");
    if (constructor != nullptr) {
        const auto exception = static_cast<jthrowable>(
            env->NewObject(exception_class, constructor, error_code));
        if (exception != nullptr) {
            env->Throw(exception);
            env->DeleteLocalRef(exception);
        }
    }
    env->DeleteLocalRef(exception_class);
}

std::shared_ptr<EngineHandle> find_handle(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto iterator = registry.find(id);
    return iterator == registry.end() ? nullptr : iterator->second;
}

std::string get_string(JNIEnv * env, jobject object, const char * getter) {
    const jclass type = env->GetObjectClass(object);
    const jmethodID method = env->GetMethodID(type, getter, "()Ljava/lang/String;");
    const auto value = static_cast<jstring>(env->CallObjectMethod(object, method));
    env->DeleteLocalRef(type);
    if (value == nullptr) {
        return {};
    }
    const char * bytes = env->GetStringUTFChars(value, nullptr);
    std::string result = bytes == nullptr ? std::string() : std::string(bytes);
    if (bytes != nullptr) {
        env->ReleaseStringUTFChars(value, bytes);
    }
    env->DeleteLocalRef(value);
    return result;
}

jint get_int(JNIEnv * env, jobject object, const char * getter) {
    const jclass type = env->GetObjectClass(object);
    const jmethodID method = env->GetMethodID(type, getter, "()I");
    const jint result = env->CallIntMethod(object, method);
    env->DeleteLocalRef(type);
    return result;
}

jfloat get_float(JNIEnv * env, jobject object, const char * getter) {
    const jclass type = env->GetObjectClass(object);
    const jmethodID method = env->GetMethodID(type, getter, "()F");
    const jfloat result = env->CallFloatMethod(object, method);
    env->DeleteLocalRef(type);
    return result;
}

jboolean get_boolean(JNIEnv * env, jobject object, const char * getter) {
    const jclass type = env->GetObjectClass(object);
    const jmethodID method = env->GetMethodID(type, getter, "()Z");
    const jboolean result = env->CallBooleanMethod(object, method);
    env->DeleteLocalRef(type);
    return result;
}

struct CancellationState {
    JavaVM * vm;
    jobject signal;
    jmethodID is_cancelled;
    EngineHandle * handle;
};

bool cancellation_requested(CancellationState * state) {
    if (state->handle->abort_requested.load(std::memory_order_relaxed)) {
        return true;
    }
    JNIEnv * env = nullptr;
    bool attached = false;
    const jint status = state->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (state->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            state->handle->abort_requested.store(true, std::memory_order_relaxed);
            return true;
        }
        attached = true;
    } else if (status != JNI_OK) {
        state->handle->abort_requested.store(true, std::memory_order_relaxed);
        return true;
    }
    const bool cancelled = env->CallBooleanMethod(state->signal, state->is_cancelled) == JNI_TRUE;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        state->handle->abort_requested.store(true, std::memory_order_relaxed);
    } else if (cancelled) {
        state->handle->abort_requested.store(true, std::memory_order_relaxed);
    }
    if (attached) {
        state->vm->DetachCurrentThread();
    }
    return state->handle->abort_requested.load(std::memory_order_relaxed);
}

bool abort_callback(void * user_data) {
    return cancellation_requested(static_cast<CancellationState *>(user_data));
}

bool encoder_begin_callback(whisper_context *, whisper_state *, void * user_data) {
    return !cancellation_requested(static_cast<CancellationState *>(user_data));
}

void progress_callback(whisper_context *, whisper_state *, int, void * user_data) {
    cancellation_requested(static_cast<CancellationState *>(user_data));
}

jobject make_result(JNIEnv * env, whisper_context * context) {
    const jclass list_class = env->FindClass("java/util/ArrayList");
    const jmethodID list_constructor = env->GetMethodID(list_class, "<init>", "(I)V");
    const jmethodID list_add = env->GetMethodID(list_class, "add", "(Ljava/lang/Object;)Z");
    const jint segment_count = whisper_full_n_segments(context);
    const jobject segments = env->NewObject(list_class, list_constructor, segment_count);

    const jclass segment_class = env->FindClass(
        "io/github/surioustype/localscribe/engine/NativeSegment");
    const jmethodID segment_constructor = env->GetMethodID(
        segment_class,
        "<init>",
        "(JJLjava/lang/String;)V");
    for (jint index = 0; index < segment_count; ++index) {
        const jlong start_ms = whisper_full_get_segment_t0(context, index) * 10;
        const jlong end_ms = whisper_full_get_segment_t1(context, index) * 10;
        const char * text = whisper_full_get_segment_text(context, index);
        const jstring java_text = env->NewStringUTF(text == nullptr ? "" : text);
        const jobject segment = env->NewObject(
            segment_class,
            segment_constructor,
            start_ms,
            end_ms,
            java_text);
        env->CallBooleanMethod(segments, list_add, segment);
        env->DeleteLocalRef(segment);
        env->DeleteLocalRef(java_text);
    }

    jstring language = nullptr;
    const int language_id = whisper_full_lang_id(context);
    if (language_id >= 0) {
        const char * language_code = whisper_lang_str(language_id);
        if (language_code != nullptr) {
            language = env->NewStringUTF(language_code);
        }
    }
    const jclass result_class = env->FindClass(
        "io/github/surioustype/localscribe/engine/NativeTranscriptionResult");
    const jmethodID result_constructor = env->GetMethodID(
        result_class,
        "<init>",
        "(Ljava/util/List;Ljava/lang/String;)V");
    const jobject result = env->NewObject(result_class, result_constructor, segments, language);
    if (language != nullptr) {
        env->DeleteLocalRef(language);
    }
    env->DeleteLocalRef(result_class);
    env->DeleteLocalRef(segment_class);
    env->DeleteLocalRef(segments);
    env->DeleteLocalRef(list_class);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_surioustype_localscribe_engine_JniWhisperNativeBridge_load(
    JNIEnv * env,
    jobject,
    jstring model_path) {
    try {
        std::call_once(logging_once, [] { whisper_log_set(disabled_log, nullptr); });
        if (model_path == nullptr) {
            throw_native_error(env, native_error_failure);
            return 0;
        }
        const char * path = env->GetStringUTFChars(model_path, nullptr);
        if (path == nullptr) {
            throw_native_error(env, native_error_out_of_memory);
            return 0;
        }
        whisper_context_params params = whisper_context_default_params();
        params.use_gpu = false;
        params.flash_attn = false;
        WhisperContext context(whisper_init_from_file_with_params(path, params));
        env->ReleaseStringUTFChars(model_path, path);
        if (context == nullptr) {
            throw_native_error(env, native_error_invalid_model);
            return 0;
        }
        auto handle = std::make_shared<EngineHandle>(std::move(context));
        const jlong id = next_handle.fetch_add(1);
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            registry.emplace(id, handle);
        }
        return id;
    } catch (const std::bad_alloc &) {
        throw_native_error(env, native_error_out_of_memory);
    } catch (...) {
        throw_native_error(env, native_error_failure);
    }
    return 0;
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_surioustype_localscribe_engine_JniWhisperNativeBridge_transcribe(
    JNIEnv * env,
    jobject,
    jlong handle_id,
    jfloatArray pcm,
    jobject request,
    jobject cancellation_signal) {
    const auto handle = find_handle(handle_id);
    if (handle == nullptr || pcm == nullptr || request == nullptr || cancellation_signal == nullptr) {
        throw_native_error(env, native_error_failure);
        return nullptr;
    }
    std::lock_guard<std::mutex> lifecycle_lock(handle->lifecycle_mutex);
    if (handle->context == nullptr) {
        throw_native_error(env, native_error_failure);
        return nullptr;
    }
    handle->abort_requested.store(false, std::memory_order_relaxed);
    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        throw_native_error(env, native_error_out_of_memory);
        return nullptr;
    }
    jobject signal = env->NewGlobalRef(cancellation_signal);
    const jclass signal_class = env->GetObjectClass(cancellation_signal);
    const jmethodID is_cancelled = env->GetMethodID(signal_class, "isCancelled", "()Z");
    env->DeleteLocalRef(signal_class);
    JavaVM * vm = nullptr;
    env->GetJavaVM(&vm);
    CancellationState cancellation{vm, signal, is_cancelled, handle.get()};

    try {
        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = get_int(env, request, "getThreadCount");
        params.translate = get_boolean(env, request, "getTranslateToEnglish") == JNI_TRUE;
        params.temperature = get_float(env, request, "getTemperature");
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.no_context = true;
        const std::string language = get_string(env, request, "getLanguage");
        const std::string prompt = get_string(env, request, "getPrompt");
        const std::string vad_model = get_string(env, request, "getVadModelPath");
        params.language = language.empty() ? nullptr : language.c_str();
        params.detect_language = language.empty();
        params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
        params.vad = !vad_model.empty();
        params.vad_model_path = vad_model.empty() ? nullptr : vad_model.c_str();
        params.vad_params.threshold = get_float(env, request, "getVadThreshold");
        params.vad_params.min_speech_duration_ms = get_int(
            env,
            request,
            "getMinimumSpeechDurationMs");
        params.vad_params.min_silence_duration_ms = get_int(
            env,
            request,
            "getMinimumSilenceDurationMs");
        params.abort_callback = abort_callback;
        params.abort_callback_user_data = &cancellation;
        params.encoder_begin_callback = encoder_begin_callback;
        params.encoder_begin_callback_user_data = &cancellation;
        params.progress_callback = progress_callback;
        params.progress_callback_user_data = &cancellation;

        const int result = whisper_full(
            handle->context.get(),
            params,
            samples,
            env->GetArrayLength(pcm));
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->DeleteGlobalRef(signal);
        if (result != 0) {
            throw_native_error(
                env,
                handle->abort_requested.load(std::memory_order_relaxed)
                    ? native_error_cancelled
                    : native_error_failure);
            return nullptr;
        }
        return make_result(env, handle->context.get());
    } catch (const std::bad_alloc &) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->DeleteGlobalRef(signal);
        throw_native_error(env, native_error_out_of_memory);
    } catch (...) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        env->DeleteGlobalRef(signal);
        throw_native_error(env, native_error_failure);
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_surioustype_localscribe_engine_JniWhisperNativeBridge_abort(
    JNIEnv *,
    jobject,
    jlong handle_id) {
    const auto handle = find_handle(handle_id);
    if (handle != nullptr) {
        handle->abort_requested.store(true, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_surioustype_localscribe_engine_JniWhisperNativeBridge_unload(
    JNIEnv *,
    jobject,
    jlong handle_id) {
    std::shared_ptr<EngineHandle> handle;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        const auto iterator = registry.find(handle_id);
        if (iterator == registry.end()) {
            return;
        }
        handle = iterator->second;
        handle->abort_requested.store(true, std::memory_order_relaxed);
        registry.erase(iterator);
    }
    std::lock_guard<std::mutex> lifecycle_lock(handle->lifecycle_mutex);
    if (handle->context != nullptr) {
        handle->context.reset();
    }
}
