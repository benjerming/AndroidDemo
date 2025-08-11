use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use log::{debug, error, info, warn};

/// 文件类型枚举
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum FileType {
    Directory,
    RegularFile,
    SymbolicLink,
    Other,
}

/// 文件信息结构体
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileInfo {
    pub name: String,
    pub path: PathBuf,
    pub file_type: FileType,
    pub size: u64,
    pub modified_time: u64,
    pub extension: Option<String>,
    pub mime_type: Option<String>,
    pub is_hidden: bool,
}

/// 目录扫描配置
#[derive(Debug, Clone)]
pub struct ScanConfig {
    pub recursive: bool,
    pub include_hidden: bool,
    pub max_depth: Option<usize>,
    pub follow_symlinks: bool,
    pub file_filters: Vec<String>,
    pub size_limit: Option<u64>,
}

impl Default for ScanConfig {
    fn default() -> Self {
        Self {
            recursive: false,
            include_hidden: false,
            max_depth: None,
            follow_symlinks: false,
            file_filters: Vec::new(),
            size_limit: None,
        }
    }
}

/// 扫描结果统计
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanStats {
    pub total_files: usize,
    pub total_directories: usize,
    pub total_size: u64,
    pub largest_file: Option<FileInfo>,
    pub scan_duration_ms: u64,
    pub errors_count: usize,
}

/// 目录扫描结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanResult {
    pub root_path: PathBuf,
    pub files: Vec<FileInfo>,
    pub stats: ScanStats,
    pub errors: Vec<String>,
}

/// 主要的目录扫描器
pub struct DirectoryScanner {
    config: ScanConfig,
}

impl DirectoryScanner {
    /// 创建新的目录扫描器
    pub fn new(config: ScanConfig) -> Self {
        Self { config }
    }

    /// 扫描指定目录
    pub fn scan<P: AsRef<Path>>(&self, path: P) -> ScanResult {
        let start_time = SystemTime::now();
        let root_path = path.as_ref().to_path_buf();

        info!("开始扫描目录: {:?}", root_path);

        let mut files = Vec::new();
        let mut errors = Vec::new();

        // 验证路径
        if let Err(e) = self.validate_path(&root_path) {
            errors.push(e);
            return ScanResult {
                root_path,
                files,
                stats: self.create_empty_stats(start_time),
                errors,
            };
        }

        // 执行扫描
        self.scan_directory(&root_path, &mut files, &mut errors, 0);

        // 应用过滤器
        files = self.apply_filters(files);

        // 排序文件
        files.sort_by(|a, b| match (&a.file_type, &b.file_type) {
            (FileType::Directory, FileType::Directory)
            | (FileType::RegularFile, FileType::RegularFile) => a.name.cmp(&b.name),
            (FileType::Directory, _) => std::cmp::Ordering::Less,
            (_, FileType::Directory) => std::cmp::Ordering::Greater,
            _ => a.name.cmp(&b.name),
        });

        let stats = self.calculate_stats(&files, start_time, errors.len());

        info!(
            "扫描完成: 找到 {} 个文件, {} 个目录, {} 个错误",
            stats.total_files, stats.total_directories, stats.errors_count
        );

        ScanResult {
            root_path,
            files,
            stats,
            errors,
        }
    }

    /// 验证路径
    fn validate_path(&self, path: &Path) -> Result<(), String> {
        if !path.exists() {
            return Err(format!("路径不存在: {:?}", path));
        }

        if !path.is_dir() {
            return Err(format!("路径不是目录: {:?}", path));
        }

        // 检查权限
        if let Err(e) = fs::read_dir(path) {
            return Err(format!("无法访问目录: {} - {}", path.display(), e));
        }

        Ok(())
    }

    /// 递归扫描目录
    fn scan_directory(
        &self,
        path: &Path,
        files: &mut Vec<FileInfo>,
        errors: &mut Vec<String>,
        current_depth: usize,
    ) {
        // 检查深度限制
        if let Some(max_depth) = self.config.max_depth {
            if current_depth >= max_depth {
                debug!("达到最大深度限制: {}", current_depth);
                return;
            }
        }

        let entries = match fs::read_dir(path) {
            Ok(entries) => entries,
            Err(e) => {
                let error_msg = format!("无法读取目录 {:?}: {}", path, e);
                error!("{}", error_msg);
                errors.push(error_msg);
                return;
            }
        };

        // 并行处理目录条目（如果条目数量较多）
        let entry_results: Vec<_> = entries.collect::<Result<Vec<_>, _>>().unwrap_or_else(|e| {
            errors.push(format!("收集目录条目时出错: {}", e));
            Vec::new()
        });

        // 处理目录条目（使用串行处理避免复杂的并发错误处理）
        for entry in entry_results.iter() {
            if let Some(file_info) = self.process_entry(entry, errors) {
                files.push(file_info);
            }
        }

        // 递归处理子目录
        if self.config.recursive {
            let subdirs: Vec<_> = files
                .iter()
                .filter(|f| matches!(f.file_type, FileType::Directory))
                .filter(|f| f.path.parent() == Some(path))
                .map(|f| f.path.clone())
                .collect();

            for subdir in subdirs {
                self.scan_directory(&subdir, files, errors, current_depth + 1);
            }
        }
    }

    /// 处理单个目录条目
    fn process_entry(&self, entry: &fs::DirEntry, errors: &mut Vec<String>) -> Option<FileInfo> {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().to_string();

        // 检查是否为隐藏文件
        let is_hidden = name.starts_with('.');
        if is_hidden && !self.config.include_hidden {
            return None;
        }

        let metadata = match entry.metadata() {
            Ok(metadata) => metadata,
            Err(e) => {
                let error_msg = format!("无法读取文件元数据 {:?}: {}", path, e);
                warn!("{}", error_msg);
                errors.push(error_msg);
                return None;
            }
        };

        let file_type = if metadata.is_dir() {
            FileType::Directory
        } else if metadata.is_file() {
            FileType::RegularFile
        } else if metadata.file_type().is_symlink() {
            if !self.config.follow_symlinks {
                return None;
            }
            FileType::SymbolicLink
        } else {
            FileType::Other
        };

        let size = metadata.len();

        // 应用大小限制
        if let Some(size_limit) = self.config.size_limit {
            if size > size_limit {
                debug!("跳过大文件: {:?} ({}B > {}B)", path, size, size_limit);
                return None;
            }
        }

        let modified_time = metadata
            .modified()
            .ok()
            .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
            .map(|duration| duration.as_secs())
            .unwrap_or(0);

        let extension = path
            .extension()
            .and_then(|ext| ext.to_str())
            .map(|ext| ext.to_lowercase());

        let mime_type = self.detect_mime_type(&path, &extension);

        Some(FileInfo {
            name,
            path,
            file_type,
            size,
            modified_time,
            extension,
            mime_type,
            is_hidden,
        })
    }

    /// 检测MIME类型
    fn detect_mime_type(&self, _path: &Path, extension: &Option<String>) -> Option<String> {
        if let Some(ext) = extension {
            let mime_type = match ext.as_str() {
                // 字体文件
                "ttf" => "font/ttf",
                "otf" => "font/otf",
                "woff" => "font/woff",
                "woff2" => "font/woff2",
                "eot" => "application/vnd.ms-fontobject",

                // 图像文件
                "jpg" | "jpeg" => "image/jpeg",
                "png" => "image/png",
                "gif" => "image/gif",
                "bmp" => "image/bmp",
                "webp" => "image/webp",
                "svg" => "image/svg+xml",

                // 文档文件
                "pdf" => "application/pdf",
                "doc" => "application/msword",
                "docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "txt" => "text/plain",
                "json" => "application/json",
                "xml" => "application/xml",

                // 音频文件
                "mp3" => "audio/mpeg",
                "wav" => "audio/wav",
                "ogg" => "audio/ogg",

                // 视频文件
                "mp4" => "video/mp4",
                "avi" => "video/x-msvideo",
                "mov" => "video/quicktime",

                _ => return None,
            };
            Some(mime_type.to_string())
        } else {
            None
        }
    }

    /// 应用过滤器
    fn apply_filters(&self, mut files: Vec<FileInfo>) -> Vec<FileInfo> {
        if self.config.file_filters.is_empty() {
            return files;
        }

        files.retain(|file| {
            for filter in &self.config.file_filters {
                if file.name.contains(filter)
                    || file.extension.as_ref().map_or(false, |ext| ext == filter)
                    || file
                        .mime_type
                        .as_ref()
                        .map_or(false, |mime| mime.contains(filter))
                {
                    return true;
                }
            }
            false
        });

        files
    }

    /// 计算统计信息
    fn calculate_stats(
        &self,
        files: &[FileInfo],
        start_time: SystemTime,
        errors_count: usize,
    ) -> ScanStats {
        let total_files = files
            .iter()
            .filter(|f| matches!(f.file_type, FileType::RegularFile))
            .count();
        let total_directories = files
            .iter()
            .filter(|f| matches!(f.file_type, FileType::Directory))
            .count();
        let total_size = files.iter().map(|f| f.size).sum();

        let largest_file = files
            .iter()
            .filter(|f| matches!(f.file_type, FileType::RegularFile))
            .max_by_key(|f| f.size)
            .cloned();

        let scan_duration_ms = start_time
            .elapsed()
            .map(|duration| duration.as_millis() as u64)
            .unwrap_or(0);

        ScanStats {
            total_files,
            total_directories,
            total_size,
            largest_file,
            scan_duration_ms,
            errors_count,
        }
    }

    /// 创建空统计信息
    fn create_empty_stats(&self, start_time: SystemTime) -> ScanStats {
        let scan_duration_ms = start_time
            .elapsed()
            .map(|duration| duration.as_millis() as u64)
            .unwrap_or(0);

        ScanStats {
            total_files: 0,
            total_directories: 0,
            total_size: 0,
            largest_file: None,
            scan_duration_ms,
            errors_count: 0,
        }
    }
}

/// 格式化文件大小
pub fn format_file_size(size: u64) -> String {
    const UNITS: &[&str] = &["B", "KB", "MB", "GB", "TB"];
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

/// 格式化扫描结果为可读字符串
pub fn format_scan_result(result: &ScanResult) -> String {
    let mut output = String::new();

    output.push_str(&format!("📁 扫描目录: {}\n", result.root_path.display()));
    output.push_str(&format!(
        "⏱️  扫描耗时: {} ms\n",
        result.stats.scan_duration_ms
    ));
    output.push_str(&format!("📊 统计信息:\n"));
    output.push_str(&format!("   • 文件总数: {}\n", result.stats.total_files));
    output.push_str(&format!(
        "   • 目录总数: {}\n",
        result.stats.total_directories
    ));
    output.push_str(&format!(
        "   • 总大小: {}\n",
        format_file_size(result.stats.total_size)
    ));

    if let Some(largest) = &result.stats.largest_file {
        output.push_str(&format!(
            "   • 最大文件: {} ({})\n",
            largest.name,
            format_file_size(largest.size)
        ));
    }

    if result.stats.errors_count > 0 {
        output.push_str(&format!("⚠️  错误数量: {}\n", result.stats.errors_count));
    }

    output.push_str("\n📋 文件列表:\n");

    for file in &result.files {
        let icon = match file.file_type {
            FileType::Directory => "📁",
            FileType::RegularFile => match file.extension.as_deref() {
                Some("ttf") | Some("otf") | Some("woff") | Some("woff2") => "🔤",
                Some("jpg") | Some("jpeg") | Some("png") | Some("gif") | Some("bmp") => "🖼️",
                Some("pdf") => "📄",
                Some("txt") => "📝",
                Some("mp3") | Some("wav") | Some("ogg") => "🎵",
                Some("mp4") | Some("avi") | Some("mov") => "🎬",
                _ => "📄",
            },
            FileType::SymbolicLink => "🔗",
            FileType::Other => "❓",
        };

        let size_str = if matches!(file.file_type, FileType::Directory) {
            "[目录]".to_string()
        } else {
            format_file_size(file.size)
        };

        output.push_str(&format!("{} {} -> {}", icon, file.name, size_str));

        if let Some(mime) = &file.mime_type {
            output.push_str(&format!(" ({})", mime));
        }

        output.push('\n');
    }

    if !result.errors.is_empty() {
        output.push_str("\n❌ 错误信息:\n");
        for error in &result.errors {
            output.push_str(&format!("   • {}\n", error));
        }
    }

    output
}

/// 初始化日志记录器
fn init_logger() {
    #[cfg(target_os = "android")]
    {
        let _ = android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("RustDemo"),
        );
    }

    #[cfg(not(target_os = "android"))]
    {
        let _ = env_logger::try_init();
    }
}

/// 改进的目录信息加载函数
pub fn load_directory_info(directory: &str, recursive: bool, include_hidden: bool) -> String {
    // 初始化日志（如果还未初始化）
    init_logger();

    let config = ScanConfig {
        recursive,
        include_hidden,
        max_depth: if recursive { Some(5) } else { Some(1) }, // 限制递归深度
        follow_symlinks: false,
        file_filters: Vec::new(),
        size_limit: Some(100 * 1024 * 1024), // 100MB 限制
    };

    let scanner = DirectoryScanner::new(config);
    let result = scanner.scan(directory);

    format_scan_result(&result)
}

/// JNI导出函数 - 对应Java中的loadFontsInfo方法（保持向后兼容）
#[no_mangle]
pub extern "C" fn Java_androidx_appcompat_demo_MainActivity_loadFontsInfo(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
) -> jstring {
    // 初始化日志
    init_logger();

    let directory_str: String = match env.get_string(&directory) {
        Ok(java_str) => java_str.into(),
        Err(e) => {
            let error_msg = format!("无法转换Java字符串: {}", e);
            error!("{}", error_msg);
            return create_java_string(&mut env, &error_msg);
        }
    };

    info!("JNI调用: 扫描目录 {}", directory_str);

    // 使用改进的函数
    let result = load_directory_info(&directory_str, false, false);
    create_java_string(&mut env, &result)
}

/// 新增JNI函数 - 支持更多选项的目录扫描
#[no_mangle]
pub extern "C" fn Java_androidx_appcompat_demo_MainActivity_loadDirectoryInfoAdvanced(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
    recursive: bool,
    include_hidden: bool,
) -> jstring {
    // 初始化日志
    init_logger();

    let directory_str: String = match env.get_string(&directory) {
        Ok(java_str) => java_str.into(),
        Err(e) => {
            let error_msg = format!("无法转换Java字符串: {}", e);
            error!("{}", error_msg);
            return create_java_string(&mut env, &error_msg);
        }
    };

    info!(
        "JNI高级调用: 扫描目录 {} (递归: {}, 隐藏文件: {})",
        directory_str, recursive, include_hidden
    );

    let result = load_directory_info(&directory_str, recursive, include_hidden);
    create_java_string(&mut env, &result)
}

/// 辅助函数：创建Java字符串（改进错误处理）
fn create_java_string(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(java_string) => java_string.into_raw(),
        Err(e) => {
            error!("创建Java字符串失败: {}", e);
            // 尝试创建错误消息字符串
            let fallback_msg = "创建返回字符串时发生错误";
            match env.new_string(fallback_msg) {
                Ok(fallback_string) => fallback_string.into_raw(),
                Err(_) => {
                    error!("创建备用字符串也失败");
                    std::ptr::null_mut()
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::{self, File};
    use std::io::Write;
    use tempfile::TempDir;

    fn create_test_directory() -> TempDir {
        let temp_dir = TempDir::new().unwrap();
        let temp_path = temp_dir.path();

        // 创建测试文件和目录
        fs::create_dir(temp_path.join("subdir")).unwrap();

        let mut file1 = File::create(temp_path.join("test.txt")).unwrap();
        file1.write_all(b"Hello, World!").unwrap();

        let mut file2 = File::create(temp_path.join("font.ttf")).unwrap();
        file2.write_all(b"fake font data").unwrap();

        let mut hidden_file = File::create(temp_path.join(".hidden")).unwrap();
        hidden_file.write_all(b"hidden content").unwrap();

        // 在子目录中创建文件
        let mut sub_file = File::create(temp_path.join("subdir").join("nested.json")).unwrap();
        sub_file.write_all(b"{\"test\": true}").unwrap();

        temp_dir
    }

    #[test]
    fn test_directory_scanner_basic() {
        let temp_dir = create_test_directory();
        let config = ScanConfig::default();
        let scanner = DirectoryScanner::new(config);

        let result = scanner.scan(temp_dir.path());

        assert_eq!(result.errors.len(), 0);
        assert!(result.files.len() >= 3); // 至少应该有 test.txt, font.ttf, subdir

        // 检查是否正确识别了目录
        assert!(result
            .files
            .iter()
            .any(|f| matches!(f.file_type, FileType::Directory)));

        // 检查是否正确识别了文件类型
        assert!(result
            .files
            .iter()
            .any(|f| f.extension == Some("txt".to_string())));
        assert!(result
            .files
            .iter()
            .any(|f| f.extension == Some("ttf".to_string())));
    }

    #[test]
    fn test_directory_scanner_recursive() {
        let temp_dir = create_test_directory();
        let config = ScanConfig {
            recursive: true,
            ..Default::default()
        };
        let scanner = DirectoryScanner::new(config);

        let result = scanner.scan(temp_dir.path());

        // 递归模式应该找到嵌套文件
        assert!(result.files.iter().any(|f| f.name == "nested.json"));
    }

    #[test]
    fn test_directory_scanner_hidden_files() {
        let temp_dir = create_test_directory();
        let config = ScanConfig {
            include_hidden: true,
            ..Default::default()
        };
        let scanner = DirectoryScanner::new(config);

        let result = scanner.scan(temp_dir.path());

        // 应该包含隐藏文件
        assert!(result.files.iter().any(|f| f.name == ".hidden"));
    }

    #[test]
    fn test_mime_type_detection() {
        let temp_dir = create_test_directory();
        let config = ScanConfig::default();
        let scanner = DirectoryScanner::new(config);

        let result = scanner.scan(temp_dir.path());

        // 检查MIME类型检测
        let ttf_file = result
            .files
            .iter()
            .find(|f| f.extension == Some("ttf".to_string()));
        assert!(ttf_file.is_some());
        assert_eq!(ttf_file.unwrap().mime_type, Some("font/ttf".to_string()));

        let txt_file = result
            .files
            .iter()
            .find(|f| f.extension == Some("txt".to_string()));
        assert!(txt_file.is_some());
        assert_eq!(txt_file.unwrap().mime_type, Some("text/plain".to_string()));
    }

    #[test]
    fn test_file_size_formatting() {
        assert_eq!(format_file_size(0), "0 B");
        assert_eq!(format_file_size(512), "512 B");
        assert_eq!(format_file_size(1024), "1.00 KB");
        assert_eq!(format_file_size(1536), "1.50 KB");
        assert_eq!(format_file_size(1048576), "1.00 MB");
        assert_eq!(format_file_size(1073741824), "1.00 GB");
    }

    #[test]
    fn test_load_directory_info_nonexistent() {
        let result = load_directory_info("/nonexistent/path", false, false);
        assert!(result.contains("路径不存在"));
    }

    #[test]
    fn test_load_directory_info_current_dir() {
        let result = load_directory_info(".", false, false);
        assert!(result.contains("扫描目录: ."));
        assert!(result.contains("统计信息"));
    }

    #[test]
    fn test_scan_config_filters() {
        let temp_dir = create_test_directory();
        let config = ScanConfig {
            file_filters: vec!["ttf".to_string()],
            ..Default::default()
        };
        let scanner = DirectoryScanner::new(config);

        let result = scanner.scan(temp_dir.path());

        // 应该只包含.ttf文件
        assert!(result
            .files
            .iter()
            .all(|f| f.extension == Some("ttf".to_string())
                || matches!(f.file_type, FileType::Directory)));
    }

    #[test]
    fn test_size_limit() {
        let temp_dir = create_test_directory();
        let config = ScanConfig {
            size_limit: Some(5), // 5字节限制
            ..Default::default()
        };
        let scanner = DirectoryScanner::new(config);

        let result = scanner.scan(temp_dir.path());

        // 大文件应该被过滤掉
        for file in &result.files {
            if matches!(file.file_type, FileType::RegularFile) {
                assert!(file.size <= 5);
            }
        }
    }
}
