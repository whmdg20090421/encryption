cmake_minimum_required(VERSION 3.22.1)
project(authcore C CXX)

add_library(authcore SHARED
    auth_jni.cpp
    obf.c
    third_party/argon2/argon2.c
    third_party/argon2/core.c
    third_party/argon2/encoding.c
    third_party/argon2/ref.c
    third_party/argon2/thread.c
    third_party/argon2/blake2/blake2b.c
)

target_include_directories(authcore PRIVATE
    third_party/argon2/include
    third_party/argon2
    third_party/argon2/blake2
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(authcore PRIVATE
    -fvisibility=hidden
    -O2
    -fdata-sections
    -ffunction-sections
    -fno-rtti
    -fno-exceptions
    -DARGON2_NO_THREADS
)

target_link_options(authcore PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384
)

target_link_libraries(authcore log)
