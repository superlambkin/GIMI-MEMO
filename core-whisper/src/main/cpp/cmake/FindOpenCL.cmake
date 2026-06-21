# FindOpenCL.cmake - Android NDK custom module
# OpenCL シンボルは Java 側で先に System.loadLibrary("OpenCL") されるため、
# ビルド時はリンクフラグのみ指定。実行時はデバイスの libOpenCL.so が解決する。
get_filename_component(_OPENCL_CMAKE_DIR "${CMAKE_CURRENT_LIST_FILE}" PATH)
get_filename_component(_OPENCL_ROOT_DIR "${_OPENCL_CMAKE_DIR}/.." ABSOLUTE)

if(EXISTS "${_OPENCL_ROOT_DIR}/opencl/CL/cl.h")
    set(OpenCL_INCLUDE_DIRS "${_OPENCL_ROOT_DIR}/opencl")
else()
    foreach(_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencl" "${CMAKE_SOURCE_DIR}/opencl")
        if(EXISTS "${_DIR}/CL/cl.h")
            set(OpenCL_INCLUDE_DIRS "${_DIR}")
            break()
        endif()
    endforeach()
endif()
if(NOT OpenCL_INCLUDE_DIRS)
    message(FATAL_ERROR "OpenCL headers not found in opencl/CL/")
endif()

# OpenCL シンボルは Java 側で先に System.loadLibrary("OpenCL") されるため、
# リンク時はライブラリ不要。未定義シンボルは CMakeLists.txt で許可する。
set(OpenCL_LIBRARIES "")
set(OpenCL_FOUND TRUE)
mark_as_advanced(OpenCL_INCLUDE_DIRS OpenCL_LIBRARIES OpenCL_FOUND)
