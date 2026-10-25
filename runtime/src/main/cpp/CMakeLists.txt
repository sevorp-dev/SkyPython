cmake_minimum_required(VERSION 3.22.1)
project(pylauncher C)

# PYTHON_BUILD_DIR and PYTHON_SHORT_VERSION are passed by runtime/build.gradle.kts.
# The CPython release for each ABI (python.org for 64-bit, our build-python workflow
# for 32-bit; same layout) is extracted to
# ${PYTHON_BUILD_DIR}/<ANDROID_ABI>/prefix by the extractPython<Abi> task.
if(NOT DEFINED PYTHON_BUILD_DIR OR NOT DEFINED PYTHON_SHORT_VERSION)
    message(FATAL_ERROR "PYTHON_BUILD_DIR / PYTHON_SHORT_VERSION not set")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(PY_TRIPLE aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(PY_TRIPLE x86_64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(PY_TRIPLE arm-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "x86")
    set(PY_TRIPLE i686-linux-android)
else()
    message(FATAL_ERROR "No CPython build for ABI ${ANDROID_ABI}")
endif()

set(PY_PREFIX "${PYTHON_BUILD_DIR}/${ANDROID_ABI}/prefix")
set(PY_LIB "${PY_PREFIX}/lib/libpython${PYTHON_SHORT_VERSION}.so")
set(PY_INCLUDE "${PY_PREFIX}/include/python${PYTHON_SHORT_VERSION}")
if(NOT EXISTS "${PY_LIB}" OR NOT EXISTS "${PY_INCLUDE}/Python.h")
    message(FATAL_ERROR "CPython (${PY_TRIPLE}) not extracted at ${PY_PREFIX}; run :runtime:preparePython")
endif()

add_library(python SHARED IMPORTED)
set_target_properties(python PROPERTIES
    IMPORTED_LOCATION "${PY_LIB}"
    INTERFACE_INCLUDE_DIRECTORIES "${PY_INCLUDE}")

# An executable named like a shared library, so it is packaged in lib/<abi>/.
add_executable(pylauncher launcher.c)
set_target_properties(pylauncher PROPERTIES
    OUTPUT_NAME "libpylauncher.so"
    PREFIX ""
    SUFFIX "")
target_link_libraries(pylauncher PRIVATE python)

# Pseudo-terminals for the Terminal and Interpreter screens (Kotlin: runtime/Pty.kt).
add_library(skypty SHARED pty.c)
target_compile_options(skypty PRIVATE -Wall -Wextra -Werror)
