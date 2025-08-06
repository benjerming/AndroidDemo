#include <algorithm>
#include <filesystem>
#include <fstream>
#include <span>
#include <sstream>
#include <string>
#include <vector>

static std::string join(std::span<std::string> arr, std::string_view sep) {
  if (arr.empty())
    return "";

  std::ostringstream oss;
  oss << arr[0];
  for (const auto &item : arr.subspan(1)) {
    oss << sep << item;
  }
  return oss.str();
}

static std::string read_file(const std::filesystem::path &path) {
  std::ifstream file(path, std::ios::binary);
  return std::string(std::istreambuf_iterator<char>(file),
                     std::istreambuf_iterator<char>());
}

std::string load_fonts_info(const std::string &directory) {
  std::vector<std::string> fontfiles;

  try {
    // 检查目录是否存在
    if (!std::filesystem::exists(directory)) {
      return "错误: 目录 '" + directory + "' 不存在";
    }

    if (!std::filesystem::is_directory(directory)) {
      return "错误: '" + directory + "' 不是一个目录";
    }

    std::transform(
        std::filesystem::directory_iterator(directory),
        std::filesystem::directory_iterator(), std::back_inserter(fontfiles),
        [](const std::filesystem::directory_entry &entry) {
          std::ostringstream oss;
          oss << entry.path().filename().generic_string();
          if (entry.is_regular_file()) {
            try {
              oss << " -> " << std::filesystem::file_size(entry.path());
              oss << " Bytes";
            } catch (const std::exception &e) {
              oss << " -> 无法读取文件大小";
            }
          } else if (entry.is_directory()) {
            oss << " -> [目录]";
          } else {
            oss << " -> [其他类型]";
          }
          return oss.str();
        });

    if (fontfiles.empty()) {
      return "目录 '" + directory + "' 中没有找到任何文件";
    }

    std::ostringstream result;
    result << "目录: " << directory << "\n";
    result << "找到 " << fontfiles.size() << " 个项目:\n\n";
    result << join(fontfiles, "\n");

    return result.str();
  } catch (const std::exception &e) {
    return "访问目录时发生错误: " + std::string(e.what());
  }
}
