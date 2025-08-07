cmake_minimum_required(VERSION 3.22)

project("demo")

add_subdirectory(systemfonts)

add_library(demo SHARED
        jni.cpp
)

find_library(log-lib log)

target_link_libraries(demo
        systemfonts
        ${log-lib}
)
