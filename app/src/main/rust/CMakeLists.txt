cmake_minimum_required(VERSION 3.22.1)

project(demo)

set(RUST_PROJECT_ROOT "${CMAKE_SOURCE_DIR}/../../../..")
set(CARGO_MANIFEST_PATH "${RUST_PROJECT_ROOT}/Cargo.toml")

# determine Rust target
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TARGET "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86")
    set(RUST_TARGET "i686-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET "x86_64-linux-android")
else()
    message(FATAL_ERROR "Unsupport Android ABI: ${ANDROID_ABI}")
endif()

# set Rust library path
set(RUST_LIB_DIR "${RUST_PROJECT_ROOT}/target/${RUST_TARGET}/release")
set(RUST_LIB_PATH "${RUST_LIB_DIR}/lib${CMAKE_PROJECT_NAME}.so")

# set Android toolchain paths (Windows needs .cmd extension for NDK tools)
# Check if we're on Windows by looking at the toolchain path
if(CMAKE_HOST_WIN32)
    set(CLANG_SUFFIX ".cmd")
    set(AR_SUFFIX ".exe")
else()
    set(CLANG_SUFFIX "")
    set(AR_SUFFIX "")
endif()

set(ANDROID_CLANG_AARCH64 "${ANDROID_TOOLCHAIN_ROOT}/bin/aarch64-linux-android${ANDROID_NATIVE_API_LEVEL}-clang${CLANG_SUFFIX}")
set(ANDROID_CLANG_ARMV7A "${ANDROID_TOOLCHAIN_ROOT}/bin/armv7a-linux-androideabi${ANDROID_NATIVE_API_LEVEL}-clang${CLANG_SUFFIX}")
set(ANDROID_CLANG_I686 "${ANDROID_TOOLCHAIN_ROOT}/bin/i686-linux-android${ANDROID_NATIVE_API_LEVEL}-clang${CLANG_SUFFIX}")
set(ANDROID_CLANG_X86_64 "${ANDROID_TOOLCHAIN_ROOT}/bin/x86_64-linux-android${ANDROID_NATIVE_API_LEVEL}-clang${CLANG_SUFFIX}")
set(ANDROID_AR "${ANDROID_TOOLCHAIN_ROOT}/bin/llvm-ar${AR_SUFFIX}")

# check if cargo is installed
find_program(CARGO_EXECUTABLE cargo REQUIRED)
if(NOT CARGO_EXECUTABLE)
    message(FATAL_ERROR "cargo command not found, please ensure Rust toolchain is installed")
endif()

# check if rustup is installed
find_program(RUSTUP_EXECUTABLE rustup)
if(RUSTUP_EXECUTABLE)
    execute_process(
        COMMAND ${RUSTUP_EXECUTABLE} target add ${RUST_TARGET}
        WORKING_DIRECTORY ${RUST_PROJECT_ROOT}
        RESULT_VARIABLE RUSTUP_RESULT
    )
    if(NOT RUSTUP_RESULT EQUAL 0)
        message(WARNING "Failed to add Rust target: ${RUST_TARGET}")
    endif()
endif()

# set target linker
if(RUST_TARGET STREQUAL "aarch64-linux-android")
    set(TARGET_LINKER ${ANDROID_CLANG_AARCH64})
elseif(RUST_TARGET STREQUAL "armv7-linux-androideabi")
    set(TARGET_LINKER ${ANDROID_CLANG_ARMV7A})
elseif(RUST_TARGET STREQUAL "i686-linux-android")
    set(TARGET_LINKER ${ANDROID_CLANG_I686})
elseif(RUST_TARGET STREQUAL "x86_64-linux-android")
    set(TARGET_LINKER ${ANDROID_CLANG_X86_64})
endif()

# Note: Environment variables are now handled via cargo_config.toml.in template

# create .cargo/config.toml
set(CARGO_CONFIG_DIR "${RUST_PROJECT_ROOT}/.cargo")
set(CARGO_CONFIG_FILE "${CARGO_CONFIG_DIR}/config.toml")

# ensure .cargo directory exists
file(MAKE_DIRECTORY ${CARGO_CONFIG_DIR})

# generate content of .cargo/config.toml
# Configure the file using CMake configure_file for proper variable substitution
configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/cargo_config.toml.in"
    "${CARGO_CONFIG_FILE}"
    @ONLY
)

# create custom target to build Rust library
add_custom_target(
    build_rust_lib ALL
    COMMAND ${CARGO_EXECUTABLE} build --release --target=${RUST_TARGET} --manifest-path=${CARGO_MANIFEST_PATH}
    WORKING_DIRECTORY ${RUST_PROJECT_ROOT}
    COMMENT "building Rust library for ${RUST_TARGET}..."
    VERBATIM
)

# check if Rust library exists
add_custom_command(
    OUTPUT ${RUST_LIB_PATH}
    DEPENDS build_rust_lib
    COMMENT "Rust library is built: ${RUST_LIB_PATH}"
)

# create a dummy source file to satisfy CMake requirements
set(DUMMY_SOURCE_FILE "${CMAKE_BINARY_DIR}/dummy.c")
file(WRITE ${DUMMY_SOURCE_FILE} "// this is a dummy source file\n")

# create wrapper library
add_library(${CMAKE_PROJECT_NAME} SHARED ${DUMMY_SOURCE_FILE})

# set dependencies, ensure Rust library is built first
add_dependencies(${CMAKE_PROJECT_NAME} build_rust_lib)

# custom command: copy Rust library after build
add_custom_command(TARGET ${CMAKE_PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${RUST_LIB_PATH}
        $<TARGET_FILE:${CMAKE_PROJECT_NAME}>
    COMMENT "copy library to final location: $<TARGET_FILE:${CMAKE_PROJECT_NAME}>"
)

# print debug information
message(STATUS "Android ABI: ${ANDROID_ABI}")
message(STATUS "Rust Target: ${RUST_TARGET}")
message(STATUS "Rust Project Root: ${RUST_PROJECT_ROOT}")
message(STATUS "Rust Library Path: ${RUST_LIB_PATH}")
message(STATUS "Android Toolchain Root: ${ANDROID_TOOLCHAIN_ROOT}")
message(STATUS "Android Native API Level: ${ANDROID_NATIVE_API_LEVEL}")
message(STATUS "Target Linker: ${TARGET_LINKER}")
message(STATUS "Android AR: ${ANDROID_AR}")
message(STATUS "Cargo Config File: ${CARGO_CONFIG_FILE}")
