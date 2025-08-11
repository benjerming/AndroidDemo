// 模块声明
mod font_copy;
mod font_parser;
mod jni_interface;
mod scanner;

// 重新导出主要功能，保持API兼容性
pub use font_copy::{copy_font_files, FontCopier};
pub use font_parser::parse_fonts_and_format;
pub use scanner::{format_file_size, DirectoryScanner, FileInfo};

// JNI函数自动导出，无需显式重新导出
// 这些函数在 jni_interface 模块中定义：
// - Java_androidx_appcompat_demo_MainActivity_loadFontsInfo
// - Java_androidx_appcompat_demo_MainActivity_copyFontFiles
// - Java_androidx_appcompat_demo_MainActivity_parseFontsDirectory

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::io::Write;
    use tempfile::TempDir;

    fn create_test_directory() -> TempDir {
        let temp_dir = TempDir::new().unwrap();
        let temp_path = temp_dir.path();

        // 创建测试字体文件
        let mut font1 = File::create(temp_path.join("arial.ttf")).unwrap();
        font1.write_all(b"fake arial font data").unwrap();

        let mut font2 = File::create(temp_path.join("calibri.otf")).unwrap();
        font2.write_all(b"fake calibri font data").unwrap();

        let mut font3 = File::create(temp_path.join("roboto.woff2")).unwrap();
        font3.write_all(b"fake roboto font data").unwrap();

        // 创建非字体文件（应该被忽略）
        let mut text_file = File::create(temp_path.join("readme.txt")).unwrap();
        text_file.write_all(b"not a font file").unwrap();

        temp_dir
    }

    #[test]
    fn test_scanner_finds_fonts() {
        let temp_dir = create_test_directory();
        let font_files = DirectoryScanner::scan_fonts(temp_dir.path());

        assert_eq!(font_files.len(), 3); // 应该只找到3个字体文件

        let font_names: Vec<&str> = font_files.iter().map(|f| f.name.as_str()).collect();
        assert!(font_names.contains(&"arial.ttf"));
        assert!(font_names.contains(&"calibri.otf"));
        assert!(font_names.contains(&"roboto.woff2"));
    }

    #[test]
    fn test_font_copier_basic() {
        let source_dir = create_test_directory();
        let target_dir = TempDir::new().unwrap();

        let copier = FontCopier::new(false);
        let result = copier.copy_fonts(source_dir.path(), target_dir.path());

        assert_eq!(result.total_files, 3);
        assert_eq!(result.successful_copies, 3);
        assert_eq!(result.failed_copies, 0);

        // 验证文件确实被复制了
        assert!(target_dir.path().join("arial.ttf").exists());
        assert!(target_dir.path().join("calibri.otf").exists());
        assert!(target_dir.path().join("roboto.woff2").exists());
    }

    #[test]
    fn test_font_copier_no_overwrite() {
        let source_dir = create_test_directory();
        let target_dir = TempDir::new().unwrap();

        // 先复制一次
        let copier = FontCopier::new(false);
        let result1 = copier.copy_fonts(source_dir.path(), target_dir.path());
        assert_eq!(result1.successful_copies, 3);

        // 再次复制，不覆盖
        let result2 = copier.copy_fonts(source_dir.path(), target_dir.path());
        assert_eq!(result2.failed_copies, 3); // 应该都失败，因为文件已存在
    }

    #[test]
    fn test_font_copier_with_overwrite() {
        let source_dir = create_test_directory();
        let target_dir = TempDir::new().unwrap();

        // 先复制一次
        let copier1 = FontCopier::new(false);
        let result1 = copier1.copy_fonts(source_dir.path(), target_dir.path());
        assert_eq!(result1.successful_copies, 3);

        // 再次复制，覆盖
        let copier2 = FontCopier::new(true);
        let result2 = copier2.copy_fonts(source_dir.path(), target_dir.path());
        assert_eq!(result2.successful_copies, 3);
        assert_eq!(result2.failed_copies, 0);
    }

    #[test]
    fn test_copy_font_files_function() {
        let source_dir = create_test_directory();
        let target_dir = TempDir::new().unwrap();

        let result = copy_font_files(
            source_dir.path().to_str().unwrap(),
            target_dir.path().to_str().unwrap(),
            false,
        );

        assert!(result.contains("字体文件复制"));
        assert!(result.contains("成功: 3"));
        assert!(result.contains("arial.ttf"));
        assert!(result.contains("✅"));
    }

    #[test]
    fn test_format_file_size() {
        assert_eq!(format_file_size(0), "0 B");
        assert_eq!(format_file_size(512), "512 B");
        assert_eq!(format_file_size(1024), "1.00 KB");
        assert_eq!(format_file_size(1048576), "1.00 MB");
        assert_eq!(format_file_size(1073741824), "1.00 GB");
    }
}
