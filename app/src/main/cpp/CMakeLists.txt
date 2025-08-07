cmake_minimum_required(VERSION 3.22)

if (CMAKE_SYSTEM_NAME STREQUAL "Android")
    # NDK [r28, ...]
    # nothing to do

    # NDK [r27]
    # android {
    #     ...
    #     defaultConfig {
    #         ...
    #         // This block is different from the one you use to link Gradle
    #         // to your CMake or ndk-build script.
    #         externalNativeBuild {
    #             // For ndk-build, instead use the ndkBuild block.
    #             cmake {
    #                 // Passes optional arguments to CMake.
    #                 arguments "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
    #             }
    #         }
    #     }
    # }

    # NDK [r23, r26]
    add_link_options("-Wl,-z,max-page-size=16384")

    # NDK [..., r22]
    # add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

    message("Supported 16KB PAGE_SIZE")
endif ()

add_subdirectory(demo)