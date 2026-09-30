# Copyright 2026 StudioRizi.
# SPDX-License-Identifier: Apache-2.0
cmake_minimum_required(VERSION 3.22)
project("pty-native")

add_library(pty-native SHARED pty-native.cpp)

set_target_properties(pty-native PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

find_library(log-lib log)

# liblog exists on Android (NDK); the guard only matters for host-side
# configure checks where it is legitimately absent.
if(log-lib)
  target_link_libraries(pty-native ${log-lib})
endif()

# Hardening: fortified libc functions + stack protector.
target_compile_options(pty-native PRIVATE
    -D_FORTIFY_SOURCE=2
    -fstack-protector-strong
)

# 16 KB page alignment (Android 15+ devices) + full RELRO + bind-now.
# ANDROID is set by AGP externalNativeBuild; the flags are ld.lld-specific
# and must not leak into host-side configure checks.
if(ANDROID)
  target_link_options(pty-native PRIVATE
      -Wl,-z,max-page-size=16384
      -Wl,-z,relro,-z,now
  )
endif()
