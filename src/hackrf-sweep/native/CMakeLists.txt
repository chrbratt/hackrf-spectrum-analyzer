# CMake driver for the MSVC build of hackrf-sweep.dll.
#
# Designed to be invoked via the vcpkg toolchain so that pthread (PThreads4W),
# libusb-1.0 and fftw3f are all picked up from a single, reproducible source.
#
# Typical invocation (from repo root):
#   cmake -S src/hackrf-sweep/native -B build/native ^
#         -G "Visual Studio 17 2022" -A x64 ^
#         -DCMAKE_TOOLCHAIN_FILE=C:/vcpkg/scripts/buildsystems/vcpkg.cmake ^
#         -DHACKRF_SOURCE_DIR=C:/path/to/hackrf-2026.01.3
#   cmake --build build/native --config Release
#
# See BUILD_NATIVE.md for the full step-by-step.

cmake_minimum_required(VERSION 3.21)

project(hackrf_sweep_lib LANGUAGES C VERSION 2026.1.3)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# --------------------------------------------------------------------
# Locate the upstream HackRF source tree.
# --------------------------------------------------------------------
# The user is expected to extract the official 2026.01.3 release into
# the repo root (sibling of src/). We look for it there by default,
# but accept an override via -DHACKRF_SOURCE_DIR=<path>.
if(NOT HACKRF_SOURCE_DIR)
    set(_default_hackrf
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../hackrf-2026.01.3/hackrf-2026.01.3")
    if(EXISTS "${_default_hackrf}/host/libhackrf/src/hackrf.c")
        set(HACKRF_SOURCE_DIR "${_default_hackrf}")
    endif()
endif()

if(NOT HACKRF_SOURCE_DIR OR
   NOT EXISTS "${HACKRF_SOURCE_DIR}/host/libhackrf/src/hackrf.c")
    message(FATAL_ERROR
        "HackRF source not found. Pass -DHACKRF_SOURCE_DIR=<path> "
        "pointing at the extracted hackrf-2026.01.3 release "
        "(the directory that contains host/libhackrf/src/hackrf.c).")
endif()

set(LIBHACKRF_DIR "${HACKRF_SOURCE_DIR}/host/libhackrf")
message(STATUS "Using libhackrf source: ${LIBHACKRF_DIR}/src/hackrf.c")

# --------------------------------------------------------------------
# External deps - all from vcpkg (x64-windows triplet).
# --------------------------------------------------------------------
find_package(PThreads4W CONFIG REQUIRED)
# vcpkg ships fftw3 as separate floating-point variants; we only need single.
find_package(FFTW3f     CONFIG REQUIRED)

# libusb has no upstream CMake config in vcpkg, only pkg-config metadata.
# Resolve it manually against the vcpkg install root.
find_path(LIBUSB_INCLUDE_DIR
    NAMES libusb.h
    PATH_SUFFIXES libusb-1.0
    REQUIRED
)
find_library(LIBUSB_LIBRARY
    NAMES libusb-1.0 usb-1.0
    REQUIRED
)
add_library(libusb_imported UNKNOWN IMPORTED)
set_target_properties(libusb_imported PROPERTIES
    IMPORTED_LOCATION             "${LIBUSB_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${LIBUSB_INCLUDE_DIR}"
)
add_library(libusb::libusb ALIAS libusb_imported)
message(STATUS "libusb header:  ${LIBUSB_INCLUDE_DIR}")
message(STATUS "libusb library: ${LIBUSB_LIBRARY}")

# --------------------------------------------------------------------
# libhackrf as a static library compiled into our DLL.
# --------------------------------------------------------------------
add_library(hackrf_static STATIC
    "${LIBHACKRF_DIR}/src/hackrf.c"
)
target_include_directories(hackrf_static PUBLIC
    "${LIBHACKRF_DIR}/src"
)
target_link_libraries(hackrf_static PUBLIC
    PThreads4W::PThreads4W
    libusb::libusb
)
target_compile_definitions(hackrf_static PRIVATE
    _CRT_SECURE_NO_WARNINGS
    HAVE_STRUCT_TIMESPEC   # tells PThreads4W not to redefine timespec
)
if(MSVC)
    # libhackrf was written for GCC; quiet the deluge of MSVC warnings.
    # /wd5286 /wd5287: enum-conversion noise from libusb constants.
    # /wd4244 /wd4267: int-narrowing noise.
    target_compile_options(hackrf_static PRIVATE
        /W1 /wd4244 /wd4267 /wd5286 /wd5287)
endif()

# --------------------------------------------------------------------
# Our hackrf-sweep.dll wrapper.
# --------------------------------------------------------------------
add_library(hackrf_sweep SHARED
    hackrf_sweep_lib.c
    hackrf_sweep.h
)
set_target_properties(hackrf_sweep PROPERTIES
    OUTPUT_NAME "hackrf-sweep"
    PREFIX ""
)
target_include_directories(hackrf_sweep PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
)
target_link_libraries(hackrf_sweep PRIVATE
    hackrf_static
    FFTW3::fftw3f
    PThreads4W::PThreads4W
    libusb::libusb
)
target_compile_definitions(hackrf_sweep PRIVATE
    _CRT_SECURE_NO_WARNINGS
    HAVE_STRUCT_TIMESPEC
)
if(MSVC)
    target_compile_options(hackrf_sweep PRIVATE /W2)
endif()

# Place the DLL where the gradle task expects to copy it from.
set_target_properties(hackrf_sweep PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY         "${CMAKE_BINARY_DIR}/dist"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG   "${CMAKE_BINARY_DIR}/dist"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/dist"
)

# --------------------------------------------------------------------
# Stage all runtime DLLs (vcpkg + our own) into one folder so the
# gradle task can lift them in a single copy step.
# --------------------------------------------------------------------
add_custom_command(TARGET hackrf_sweep POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "$<TARGET_RUNTIME_DLLS:hackrf_sweep>"
        "$<TARGET_FILE_DIR:hackrf_sweep>"
    COMMAND_EXPAND_LISTS
    COMMENT "Staging runtime DLLs next to hackrf-sweep.dll"
)
