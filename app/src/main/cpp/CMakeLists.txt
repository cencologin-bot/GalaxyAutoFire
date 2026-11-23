cmake_minimum_required(VERSION 3.22.1)
project(visual_automation)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3")

find_package(OpenCV REQUIRED CONFIG)

add_library(visual_automation SHARED
    native-lib.cpp
    vision/DetectionCore.cpp
)

target_include_directories(visual_automation PRIVATE
    ${OpenCV_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(visual_automation
    ${OpenCV_LIBS}
    jnigraphics
    android
    log
)
