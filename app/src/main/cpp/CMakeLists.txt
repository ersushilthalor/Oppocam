cmake_minimum_required(VERSION 3.22.1)

project("my-native-lib")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/mgc_eis_reconstruction)

add_library(
    my-native-lib
    SHARED
    mgc_eis_reconstruction_jni.cpp
    mgc_eis_reconstruction/mgc_eis_reconstruction.cpp
    mgc_eis_reconstruction/mgc_type18_engine.cpp
    mgc_eis_reconstruction/mgc_type18_gyro.cpp
    mgc_eis_reconstruction/mgc_type18_lookahead.cpp
    mgc_eis_reconstruction/mgc_type18_projection.cpp
)

set_source_files_properties(
    mgc_eis_reconstruction_jni.cpp
    mgc_eis_reconstruction/mgc_eis_reconstruction.cpp
    mgc_eis_reconstruction/mgc_type18_engine.cpp
    mgc_eis_reconstruction/mgc_type18_gyro.cpp
    mgc_eis_reconstruction/mgc_type18_lookahead.cpp
    mgc_eis_reconstruction/mgc_type18_projection.cpp
    PROPERTIES
    COMPILE_OPTIONS "-O3;-fno-fast-math;-ffp-contract=off"
)

target_link_libraries(
    my-native-lib
    log
)
