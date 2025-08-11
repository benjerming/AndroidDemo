use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use log::{error, info};
use std::sync::Once;

use crate::font_copy::copy_font_files;
use crate::font_parser::parse_fonts_and_format;
use crate::scanner::{format_file_size, DirectoryScanner};

static INIT_LOGGER: Once = Once::new();

/// 初始化日志记录器 - 只初始化一次
fn init_logger() {
    INIT_LOGGER.call_once(|| {
        #[cfg(target_os = "android")]
        {
            // android_logger::init_once 返回 ()，不是 Result
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Debug)
                    .with_tag("RustDemo"),
            );
        }

        #[cfg(not(target_os = "android"))]
        {
            let _ = env_logger::try_init();
        }
    });
}

/// 创建Java字符串
fn create_java_string(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(java_string) => java_string.into_raw(),
        Err(e) => {
            error!("创建Java字符串失败: {}", e);
            match env.new_string("字符串转换错误") {
                Ok(fallback) => fallback.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
    }
}

/// 简化的字体信息加载
fn load_fonts_info(directory: &str) -> String {
    init_logger();

    info!("扫描目录: {}", directory);

    let font_files = DirectoryScanner::scan_fonts(directory);

    if font_files.is_empty() {
        return format!("📁 目录: {}\n❌ 未找到字体文件", directory);
    }

    let mut output = String::new();
    output.push_str(&format!("🗡🗡🗡 Rust库\n"));
    output.push_str(&format!("📁 目录: {}\n", directory));
    output.push_str(&format!("🔤 找到 {} 个字体文件:\n\n", font_files.len()));

    let total_size: u64 = font_files.iter().map(|f| f.size).sum();

    for file in &font_files {
        let ext = file.extension.as_deref().unwrap_or("unknown");
        output.push_str(&format!(
            "• {} ({}) - {}\n",
            file.name,
            ext.to_uppercase(),
            format_file_size(file.size)
        ));
    }

    output.push_str(&format!("\n📊 总计: {}", format_file_size(total_size)));
    output
}

/// JNI函数 - 加载字体信息（保持向后兼容）
#[no_mangle]
pub extern "C" fn Java_androidx_appcompat_demo_MainActivity_loadFontsInfo(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
) -> jstring {
    let directory_str: String = match env.get_string(&directory) {
        Ok(java_str) => java_str.into(),
        Err(e) => {
            let error_msg = format!("参数转换失败: {}", e);
            error!("{}", error_msg);
            return create_java_string(&mut env, &error_msg);
        }
    };

    let result = load_fonts_info(&directory_str);
    create_java_string(&mut env, &result)
}

/// JNI函数 - 复制字体文件
#[no_mangle]
pub extern "C" fn Java_androidx_appcompat_demo_MainActivity_copyFontFiles(
    mut env: JNIEnv,
    _class: JClass,
    source_directory: JString,
    target_directory: JString,
    overwrite_existing: bool,
) -> jstring {
    init_logger();

    let source_dir_str: String = match env.get_string(&source_directory) {
        Ok(java_str) => java_str.into(),
        Err(e) => {
            let error_msg = format!("源目录参数转换失败: {}", e);
            error!("{}", error_msg);
            return create_java_string(&mut env, &error_msg);
        }
    };

    let target_dir_str: String = match env.get_string(&target_directory) {
        Ok(java_str) => java_str.into(),
        Err(e) => {
            let error_msg = format!("目标目录参数转换失败: {}", e);
            error!("{}", error_msg);
            return create_java_string(&mut env, &error_msg);
        }
    };

    info!(
        "复制字体: {} -> {} (覆盖: {})",
        source_dir_str, target_dir_str, overwrite_existing
    );

    let result = copy_font_files(&source_dir_str, &target_dir_str, overwrite_existing);
    create_java_string(&mut env, &result)
}

/// JNI函数 - 解析字体文件并提取字体名称映射
#[no_mangle]
pub extern "C" fn Java_androidx_appcompat_demo_MainActivity_parseFontsDirectory(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
) -> jstring {
    init_logger();

    let directory_str: String = match env.get_string(&directory) {
        Ok(java_str) => java_str.into(),
        Err(e) => {
            let error_msg = format!("目录参数转换失败: {}", e);
            error!("{}", error_msg);
            return create_java_string(&mut env, &error_msg);
        }
    };

    info!("开始解析字体目录: {}", directory_str);

    let result = parse_fonts_and_format(&directory_str);
    create_java_string(&mut env, &result)
}
