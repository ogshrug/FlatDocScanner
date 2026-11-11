# The Ferrite build of FlatDocScanner runs its models on Ferrite
# (third_party/ferrite, a git submodule) instead of ONNX Runtime. This compiles the parts
# of Ferrite that run an ONNX model, plus the JNI glue, into libferrite_jni.so.
cmake_minimum_required(VERSION 3.22)
project(ferrite_jni C)

# The ONNX Runtime build shares this CMake project but builds nothing from it.
if(FERRITE_SKIP)
    return()
endif()

set(FERRITE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ferrite)
if(NOT EXISTS ${FERRITE}/runtime/engine.c)
    message(FATAL_ERROR "Ferrite sources missing: run `git submodule update --init`")
endif()

add_library(ferrite_jni SHARED
    ferrite_jni.c
    ${FERRITE}/core/tensor.c
    ${FERRITE}/core/tensor_ser.c
    ${FERRITE}/core/allocator.c
    ${FERRITE}/core/log.c
    ${FERRITE}/core/fp16.c
    ${FERRITE}/core/platform.c
    ${FERRITE}/graph/graph.c
    ${FERRITE}/importer/onnx.c
    ${FERRITE}/optim/constant_fold.c
    ${FERRITE}/optim/cse.c
    ${FERRITE}/optim/dead_elim.c
    ${FERRITE}/optim/fusion.c
    ${FERRITE}/optim/optim.c
    ${FERRITE}/optim/shape_infer.c
    ${FERRITE}/optim/simplify.c
    ${FERRITE}/planner/memory_planner.c
    ${FERRITE}/runtime/engine.c
    ${FERRITE}/runtime/exec_plan.c
    ${FERRITE}/runtime/kernels.c
    ${FERRITE}/runtime/model_ser.c
    ${FERRITE}/ops/matmul.c
    ${FERRITE}/ops/activations.c
    ${FERRITE}/ops/conv1d.c
    ${FERRITE}/ops/conv2d.c
    ${FERRITE}/ops/elementwise.c
    ${FERRITE}/ops/gemm.c
    ${FERRITE}/ops/math.c
    ${FERRITE}/ops/norm.c
    ${FERRITE}/ops/pool.c
    ${FERRITE}/ops/rand.c
    ${FERRITE}/ops/reduce.c
    ${FERRITE}/ops/sequence.c
    ${FERRITE}/ops/stability.c
    ${FERRITE}/ops/vision.c
    ${FERRITE}/ops/threads.c
    ${FERRITE}/simd/backend.c
    ${FERRITE}/simd/scalar.c
)

target_include_directories(ferrite_jni PRIVATE
    ${FERRITE}/core ${FERRITE}/graph ${FERRITE}/importer ${FERRITE}/optim
    ${FERRITE}/planner ${FERRITE}/runtime ${FERRITE}/ops ${FERRITE}/simd ${FERRITE}/tools)

# Only what loading and running a model needs; quantization, the profiler and the
# linear-IR compiler are compiled out.
target_compile_definitions(ferrite_jni PRIVATE
    FERRITE_NO_QUANT FERRITE_NO_PROFILER FERRITE_NO_COMPILER _POSIX_C_SOURCE=199309L)
target_compile_options(ferrite_jni PRIVATE -O3 -fvisibility=hidden)

# Vector kernels per CPU type: NEON on 64-bit ARM (always present there), AVX2 on
# x86-64 (checked at run time, so CPUs without it fall back to scalar), scalar elsewhere.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_sources(ferrite_jni PRIVATE ${FERRITE}/simd/matmul_neon.c)
    target_compile_definitions(ferrite_jni PRIVATE FERRITE_NO_AVX2)
elseif(ANDROID_ABI STREQUAL "x86_64")
    target_sources(ferrite_jni PRIVATE
        ${FERRITE}/simd/matmul_avx2.c ${FERRITE}/simd/elementwise_avx2.c)
    set_source_files_properties(
        ${FERRITE}/simd/matmul_avx2.c ${FERRITE}/simd/elementwise_avx2.c
        PROPERTIES COMPILE_OPTIONS "-mavx2;-mfma")
    target_compile_definitions(ferrite_jni PRIVATE FERRITE_NO_NEON)
else()
    target_compile_definitions(ferrite_jni PRIVATE FERRITE_NO_AVX2 FERRITE_NO_NEON)
endif()

target_link_libraries(ferrite_jni PRIVATE log m)
# 16 KB pages (Android 15+ devices may use them).
target_link_options(ferrite_jni PRIVATE "-Wl,-z,max-page-size=16384")
