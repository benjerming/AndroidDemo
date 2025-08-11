use log::{info, warn};
use serde::{Deserialize, Serialize};

use std::fs;
use std::path::Path;

/// 字体映射信息结构体
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FontMapping {
    pub file_path: String,
    pub font_name: String,
    pub family_name: Option<String>,
    pub style_name: Option<String>,
    pub is_bold: bool,
    pub is_italic: bool,
}

/// 字体解析结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FontParseResult {
    pub total_files: usize,
    pub successful_parses: usize,
    pub failed_parses: usize,
    pub mappings: Vec<FontMapping>,
    pub errors: Vec<String>,
}

/// 字体解析器
pub struct FontParser;

impl FontParser {
    /// 解析指定目录中的所有字体文件
    pub fn parse_fonts_directory<P: AsRef<Path>>(directory: P) -> FontParseResult {
        let mut result = FontParseResult {
            total_files: 0,
            successful_parses: 0,
            failed_parses: 0,
            mappings: Vec::new(),
            errors: Vec::new(),
        };

        info!("开始解析字体目录: {:?}", directory.as_ref());

        // 获取所有字体文件
        let font_files = Self::collect_font_files(directory.as_ref());
        result.total_files = font_files.len();

        info!("找到 {} 个字体文件", font_files.len());

        // 解析每个字体文件
        for font_file in font_files {
            match Self::parse_font_file(&font_file) {
                Ok(mapping) => {
                    result.mappings.push(mapping);
                    result.successful_parses += 1;
                }
                Err(error) => {
                    let error_msg = format!("解析文件 {} 失败: {}", font_file.display(), error);
                    warn!("{}", error_msg);
                    result.errors.push(error_msg);
                    result.failed_parses += 1;
                }
            }
        }

        info!(
            "字体解析完成: 成功 {}, 失败 {}",
            result.successful_parses, result.failed_parses
        );

        result
    }

    /// 收集目录中的所有字体文件
    fn collect_font_files(directory: &Path) -> Vec<std::path::PathBuf> {
        let mut font_files = Vec::new();
        Self::collect_font_files_recursive(directory, &mut font_files, 0);
        font_files
    }

    /// 递归收集字体文件
    fn collect_font_files_recursive(
        directory: &Path,
        font_files: &mut Vec<std::path::PathBuf>,
        depth: usize,
    ) {
        // 限制递归深度
        if depth > 3 {
            return;
        }

        let entries = match fs::read_dir(directory) {
            Ok(entries) => entries,
            Err(e) => {
                warn!("无法读取目录 {:?}: {}", directory, e);
                return;
            }
        };

        for entry in entries.flatten() {
            let path = entry.path();

            if path.is_dir() {
                Self::collect_font_files_recursive(&path, font_files, depth + 1);
            } else if path.is_file() && Self::is_font_file(&path) {
                font_files.push(path);
            }
        }
    }

    /// 检查是否为字体文件
    fn is_font_file(path: &Path) -> bool {
        if let Some(extension) = path.extension() {
            if let Some(ext_str) = extension.to_str() {
                let ext_lower = ext_str.to_lowercase();
                return matches!(ext_lower.as_str(), "ttf" | "otf" | "ttc" | "otc");
            }
        }
        false
    }

    /// 解析单个字体文件
    fn parse_font_file(font_path: &Path) -> Result<FontMapping, String> {
        // 读取字体文件
        let font_data = fs::read(font_path).map_err(|e| format!("读取文件失败: {}", e))?;

        // 解析字体数据
        let face = ttf_parser::Face::parse(&font_data, 0)
            .map_err(|e| format!("解析字体数据失败: {:?}", e))?;

        // 提取字体名称信息
        let font_name = Self::extract_font_name(&face)?;
        let family_name = Self::extract_family_name(&face);
        let style_name = Self::extract_style_name(&face);

        // 判断字体样式
        let is_bold = Self::is_bold_font(&face);
        let is_italic = Self::is_italic_font(&face);

        Ok(FontMapping {
            file_path: font_path.to_string_lossy().to_string(),
            font_name,
            family_name,
            style_name,
            is_bold,
            is_italic,
        })
    }

    /// 提取字体名称
    fn extract_font_name(face: &ttf_parser::Face) -> Result<String, String> {
        // 尝试获取完整字体名称
        for name in face.names() {
            if name.name_id == ttf_parser::name_id::FULL_NAME {
                if let Some(name_str) = name.to_string() {
                    return Ok(name_str);
                }
            }
        }

        // 尝试获取PostScript名称
        for name in face.names() {
            if name.name_id == ttf_parser::name_id::POST_SCRIPT_NAME {
                if let Some(name_str) = name.to_string() {
                    return Ok(name_str);
                }
            }
        }

        // 尝试获取字体族名称
        for name in face.names() {
            if name.name_id == ttf_parser::name_id::FAMILY {
                if let Some(name_str) = name.to_string() {
                    return Ok(name_str);
                }
            }
        }

        Err("无法提取字体名称".to_string())
    }

    /// 提取字体族名称
    fn extract_family_name(face: &ttf_parser::Face) -> Option<String> {
        for name in face.names() {
            if name.name_id == ttf_parser::name_id::FAMILY {
                if let Some(name_str) = name.to_string() {
                    return Some(name_str);
                }
            }
        }
        None
    }

    /// 提取字体样式名称
    fn extract_style_name(face: &ttf_parser::Face) -> Option<String> {
        for name in face.names() {
            if name.name_id == ttf_parser::name_id::SUBFAMILY {
                if let Some(name_str) = name.to_string() {
                    return Some(name_str);
                }
            }
        }
        None
    }

    /// 判断是否为粗体字体
    fn is_bold_font(face: &ttf_parser::Face) -> bool {
        let weight = face.weight();
        weight.to_number() >= 600
    }

    /// 判断是否为斜体字体
    fn is_italic_font(face: &ttf_parser::Face) -> bool {
        face.style() == ttf_parser::Style::Italic || face.style() == ttf_parser::Style::Oblique
    }
}

/// 格式化字体解析结果
pub fn format_font_parse_result(result: &FontParseResult) -> String {
    let mut output = String::new();

    output.push_str("🔤 字体解析结果\n");
    output.push_str("=".repeat(30).as_str());
    output.push('\n');
    output.push_str(&format!("总文件数: {}\n", result.total_files));
    output.push_str(&format!("成功解析: {}\n", result.successful_parses));
    output.push_str(&format!("解析失败: {}\n", result.failed_parses));
    output.push('\n');

    if !result.mappings.is_empty() {
        output.push_str("📋 字体映射信息:\n");
        output.push_str("-".repeat(30).as_str());
        output.push('\n');

        for (index, mapping) in result.mappings.iter().enumerate() {
            output.push_str(&format!("{}. {}\n", index + 1, mapping.font_name));

            if let Some(family) = &mapping.family_name {
                output.push_str(&format!("   族名: {}\n", family));
            }

            if let Some(style) = &mapping.style_name {
                output.push_str(&format!("   样式: {}\n", style));
            }

            let mut attributes = Vec::new();
            if mapping.is_bold {
                attributes.push("粗体");
            }
            if mapping.is_italic {
                attributes.push("斜体");
            }
            if !attributes.is_empty() {
                output.push_str(&format!("   属性: {}\n", attributes.join(", ")));
            }

            // 只显示文件名，不显示完整路径
            if let Some(file_name) = std::path::Path::new(&mapping.file_path).file_name() {
                output.push_str(&format!("   文件: {}\n", file_name.to_string_lossy()));
            }
            output.push('\n');
        }
    }

    if !result.errors.is_empty() {
        output.push_str("❌ 解析错误:\n");
        output.push_str("-".repeat(30).as_str());
        output.push('\n');
        for error in &result.errors {
            output.push_str(&format!("• {}\n", error));
        }
        output.push('\n');
    }

    if result.total_files == 0 {
        output.push_str("ℹ️ 未找到字体文件\n");
    }

    output
}

/// 便捷函数：解析字体目录并返回格式化结果
pub fn parse_fonts_and_format(directory: &str) -> String {
    let result = FontParser::parse_fonts_directory(directory);
    format_font_parse_result(&result)
}



#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use tempfile::TempDir;

    fn create_test_font_directory() -> TempDir {
        let temp_dir = TempDir::new().unwrap();
        let temp_path = temp_dir.path();

        // 创建一些假的字体文件（实际上是空文件）
        File::create(temp_path.join("arial.ttf")).unwrap();
        File::create(temp_path.join("calibri.otf")).unwrap();
        File::create(temp_path.join("roboto.ttc")).unwrap();

        // 创建非字体文件
        File::create(temp_path.join("readme.txt")).unwrap();

        temp_dir
    }

    #[test]
    fn test_is_font_file() {
        assert!(FontParser::is_font_file(Path::new("arial.ttf")));
        assert!(FontParser::is_font_file(Path::new("calibri.otf")));
        assert!(FontParser::is_font_file(Path::new("roboto.ttc")));
        assert!(FontParser::is_font_file(Path::new("font.otc")));
        assert!(!FontParser::is_font_file(Path::new("readme.txt")));
        assert!(!FontParser::is_font_file(Path::new("image.png")));
    }

    #[test]
    fn test_collect_font_files() {
        let temp_dir = create_test_font_directory();
        let font_files = FontParser::collect_font_files(temp_dir.path());

        assert_eq!(font_files.len(), 3); // 应该只找到3个字体文件

        let file_names: Vec<String> = font_files
            .iter()
            .filter_map(|p| p.file_name())
            .map(|n| n.to_string_lossy().to_string())
            .collect();

        assert!(file_names.contains(&"arial.ttf".to_string()));
        assert!(file_names.contains(&"calibri.otf".to_string()));
        assert!(file_names.contains(&"roboto.ttc".to_string()));
    }

    #[test]
    fn test_format_empty_result() {
        let result = FontParseResult {
            total_files: 0,
            successful_parses: 0,
            failed_parses: 0,
            mappings: Vec::new(),
            errors: Vec::new(),
        };

        let formatted = format_font_parse_result(&result);
        assert!(formatted.contains("未找到字体文件"));
    }
}
