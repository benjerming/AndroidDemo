use log::{error, info};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;
use std::time::SystemTime;

use crate::scanner::{format_file_size, DirectoryScanner, FileInfo};

/// 简化的复制结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CopyResult {
    pub source_dir: String,
    pub target_dir: String,
    pub total_files: usize,
    pub successful_copies: usize,
    pub failed_copies: usize,
    pub total_size: u64,
    pub duration_ms: u64,
    pub details: Vec<CopyDetail>,
    pub errors: Vec<String>,
}

/// 复制详情
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CopyDetail {
    pub file_name: String,
    pub file_size: u64,
    pub success: bool,
    pub error: Option<String>,
}

/// 字体文件复制器
pub struct FontCopier {
    pub overwrite: bool,
}

impl FontCopier {
    pub fn new(overwrite: bool) -> Self {
        Self { overwrite }
    }

    /// 复制字体文件
    pub fn copy_fonts<P: AsRef<Path>>(&self, source_dir: P, target_dir: P) -> CopyResult {
        let start_time = SystemTime::now();
        let source_path = source_dir.as_ref();
        let target_path = target_dir.as_ref();

        info!("开始复制字体文件: {:?} -> {:?}", source_path, target_path);

        let mut result = CopyResult {
            source_dir: source_path.display().to_string(),
            target_dir: target_path.display().to_string(),
            total_files: 0,
            successful_copies: 0,
            failed_copies: 0,
            total_size: 0,
            duration_ms: 0,
            details: Vec::new(),
            errors: Vec::new(),
        };

        // 验证源目录
        if !source_path.exists() || !source_path.is_dir() {
            result.errors.push(format!("源目录无效: {:?}", source_path));
            return result;
        }

        // 创建目标目录
        if let Err(e) = fs::create_dir_all(target_path) {
            result.errors.push(format!("无法创建目标目录: {}", e));
            return result;
        }

        // 扫描字体文件
        let font_files = DirectoryScanner::scan_fonts(source_path);
        result.total_files = font_files.len();

        // 复制每个文件
        for file_info in font_files {
            let copy_detail = self.copy_single_file(&file_info, target_path);

            if copy_detail.success {
                result.successful_copies += 1;
                result.total_size += copy_detail.file_size;
            } else {
                result.failed_copies += 1;
            }

            result.details.push(copy_detail);
        }

        result.duration_ms = start_time
            .elapsed()
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        info!(
            "复制完成: 成功 {}, 失败 {}",
            result.successful_copies, result.failed_copies
        );
        result
    }

    /// 复制单个文件
    fn copy_single_file(&self, file_info: &FileInfo, target_dir: &Path) -> CopyDetail {
        let target_path = target_dir.join(&file_info.name);

        // 检查文件是否已存在
        if target_path.exists() && !self.overwrite {
            return CopyDetail {
                file_name: file_info.name.clone(),
                file_size: file_info.size,
                success: false,
                error: Some("文件已存在".to_string()),
            };
        }

        // 执行复制
        match fs::copy(&file_info.path, &target_path) {
            Ok(_) => {
                info!("成功复制: {}", file_info.name);
                CopyDetail {
                    file_name: file_info.name.clone(),
                    file_size: file_info.size,
                    success: true,
                    error: None,
                }
            }
            Err(e) => {
                error!("复制失败 {}: {}", file_info.name, e);
                CopyDetail {
                    file_name: file_info.name.clone(),
                    file_size: file_info.size,
                    success: false,
                    error: Some(e.to_string()),
                }
            }
        }
    }
}

/// 格式化复制结果
pub fn format_copy_result(result: &CopyResult) -> String {
    let mut output = String::new();

    output.push_str(&format!("📁 字体文件复制\n"));
    output.push_str(&format!("源目录: {}\n", result.source_dir));
    output.push_str(&format!("目标目录: {}\n", result.target_dir));
    output.push_str(&format!("耗时: {} ms\n\n", result.duration_ms));

    output.push_str(&format!("📊 统计:\n"));
    output.push_str(&format!("• 发现: {} 个字体文件\n", result.total_files));
    output.push_str(&format!("• 成功: {} 个\n", result.successful_copies));
    output.push_str(&format!("• 失败: {} 个\n", result.failed_copies));
    output.push_str(&format!(
        "• 总大小: {}\n\n",
        format_file_size(result.total_size)
    ));

    if !result.details.is_empty() {
        output.push_str("📋 详情:\n");
        for detail in &result.details {
            let icon = if detail.success { "✅" } else { "❌" };
            output.push_str(&format!(
                "{} {} ({})",
                icon,
                detail.file_name,
                format_file_size(detail.file_size)
            ));

            if let Some(error) = &detail.error {
                output.push_str(&format!(" - {}", error));
            }
            output.push('\n');
        }
    }

    if !result.errors.is_empty() {
        output.push_str("\n❌ 错误:\n");
        for error in &result.errors {
            output.push_str(&format!("• {}\n", error));
        }
    }

    output
}

/// 主要的复制函数
pub fn copy_font_files(source_dir: &str, target_dir: &str, overwrite: bool) -> String {
    let copier = FontCopier::new(overwrite);
    let result = copier.copy_fonts(source_dir, target_dir);
    format_copy_result(&result)
}
