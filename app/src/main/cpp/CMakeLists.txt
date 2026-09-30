# Compiles the whole OpenBST (BeSTspeech) engine — every C source under
# src/, INCLUDING src/data/*.c, the lifted per-build tables.
#
# Tables decision: EMBED. The iOS build offers --no-tables for public
# shipping (see iOSProject/build_frameworks.py); this port is a personal
# build, so the tables ship inside libbestdroid.so and a NOTICE file in
# the app assets + repo root records whose data that is. No network fetch
# of tables, no user-supplied image files: bst_open(build) just works.
cmake_minimum_required(VERSION 3.22.1)
project(bestdroid C)

# Pinned engine source: the vendor/openbst submodule
# (Mudb0y/openbst @ a151e78). Falls back to a local checkout for
# developers who already have one; the submodule is authoritative.
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/openbst/src")
    set(OPENBST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/openbst")
else()
    set(OPENBST_DIR "C:/src/openbst-mirror")
endif()

file(GLOB_RECURSE OPENBST_SOURCES "${OPENBST_DIR}/src/*.c")

add_library(bestdroid SHARED bestdroid.c ${OPENBST_SOURCES})

target_include_directories(bestdroid PRIVATE "${OPENBST_DIR}/include")

target_compile_options(bestdroid PRIVATE -O2 -Wall -std=gnu11)

find_library(log-lib log)
target_link_libraries(bestdroid ${log-lib})
