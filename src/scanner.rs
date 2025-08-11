use log::warn;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

/// 文件类型枚举
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum FileType {
    Directory,
    RegularFile,
}

/// 简化的文件信息结构体
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileInfo {
    pub name: String,
    pub path: PathBuf,
    pub file_type: FileType,
    pub size: u64,
    pub extension: Option<String>,
}

/// 简化的目录扫描器
pub struct DirectoryScanner;

impl DirectoryScanner {
    /// 扫描目录中的字体文件
    pub fn scan_fonts<P: AsRef<Path>>(path: P) -> Vec<FileInfo> {
        let mut files = Vec::new();
        Self::scan_directory_recursive(&path.as_ref(), &mut files);

        // 只保留字体文件
        files
            .into_iter()
            .filter(|f| matches!(f.file_type, FileType::RegularFile))
            .filter(|f| Self::is_font_file(f))
            .collect()
    }

    /// 递归扫描目录
    fn scan_directory_recursive(path: &Path, files: &mut Vec<FileInfo>) {
        let entries = match fs::read_dir(path) {
            Ok(entries) => entries,
            Err(e) => {
                warn!("无法读取目录 {:?}: {}", path, e);
                return;
            }
        };

        for entry in entries.flatten() {
            if let Some(file_info) = Self::process_entry(&entry) {
                if matches!(file_info.file_type, FileType::Directory) {
                    Self::scan_directory_recursive(&file_info.path, files);
                } else {
                    files.push(file_info);
                }
            }
        }
    }

    /// 处理单个目录条目
    fn process_entry(entry: &fs::DirEntry) -> Option<FileInfo> {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().to_string();

        // 跳过隐藏文件
        if name.starts_with('.') {
            return None;
        }

        let metadata = entry.metadata().ok()?;

        let file_type = if metadata.is_dir() {
            FileType::Directory
        } else if metadata.is_file() {
            FileType::RegularFile
        } else {
            return None;
        };

        let size = metadata.len();

        // 跳过过大的文件（50MB限制）
        if size > 50 * 1024 * 1024 {
            return None;
        }

        let extension = path
            .extension()
            .and_then(|ext| ext.to_str())
            .map(|ext| ext.to_lowercase());

        Some(FileInfo {
            name,
            path,
            file_type,
            size,
            extension,
        })
    }

    /// 检查是否为字体文件
    fn is_font_file(file_info: &FileInfo) -> bool {
        if let Some(ext) = &file_info.extension {
            matches!(ext.as_str(), "ttf" | "otf" | "woff" | "woff2" | "eot" | "ttc")
        } else {
            false
        }
    }
}

/// 格式化文件大小
pub fn format_file_size(size: u64) -> String {
    const UNITS: [&str; 4] = ["B", "KB", "MB", "GB"];
    let mut size = size as f64;
    let mut unit_index = 0;

    while size >= 1024.0 && unit_index < UNITS.len() - 1 {
        size /= 1024.0;
        unit_index += 1;
    }

    if unit_index == 0 {
        format!("{} {}", size as u64, UNITS[unit_index])
    } else {
        format!("{:.2} {}", size, UNITS[unit_index])
    }
}
