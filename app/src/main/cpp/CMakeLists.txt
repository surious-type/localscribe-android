cmake_minimum_required(VERSION 4.1.2)

project(localscribe_whisper LANGUAGES C CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whispercpp
    URL https://github.com/ggml-org/whisper.cpp/archive/927cfce34f31707e17f2bff35c349632fb9e2c3a.tar.gz
    URL_HASH SHA256=41b664fee09e79176ac277b5237debec34f8d74af3c7d71f333f1ec67989ecde
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(whispercpp)

add_library(localscribe_whisper SHARED whisper_jni.cpp)
target_compile_features(localscribe_whisper PRIVATE cxx_std_17)
target_compile_options(localscribe_whisper PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(
    localscribe_whisper
    PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
target_link_libraries(localscribe_whisper PRIVATE whisper android log)
