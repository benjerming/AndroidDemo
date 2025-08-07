add_library(systemfonts STATIC
        systemfonts.cpp
)

target_include_directories(systemfonts PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_features(systemfonts PUBLIC cxx_std_23)