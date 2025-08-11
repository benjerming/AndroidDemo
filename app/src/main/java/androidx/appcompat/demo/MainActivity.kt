package androidx.appcompat.demo


import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.appcompat.demo.ui.theme.DemoTheme
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID



// 创建临时文件并写入内容
fun createTextFile(context: Context, content: String, title: String = "应用结果"): File? {
    return try {
        // 创建文件名（包含时间戳）
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val fileName = "${title}_$timestamp.txt"
        
        // 在应用的缓存目录创建文件
        val file = File(context.cacheDir, fileName)
        
        // 写入内容
        FileWriter(file).use { writer ->
            writer.write(content)
        }
        
        file
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// 分享文本文件到其他应用
fun shareTextFile(context: Context, content: String, title: String = "应用结果"): Boolean {
    return try {
        // 创建临时文件
        val file = createTextFile(context, content, title) ?: return false
        
        // 使用FileProvider获取URI
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        
        // 创建分享Intent
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "分享的文本文件：${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        // 启动分享选择器
        val chooserIntent = Intent.createChooser(shareIntent, "分享文本文件")
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooserIntent)
        
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

class MainActivity : ComponentActivity() {

    init {
        // 加载native库
        System.loadLibrary("demo")
    }

    // 声明native方法  
    external fun loadFontsInfo(directory: String): String
    external fun copyFontFiles(sourceDirectory: String, targetDirectory: String, overwriteExisting: Boolean): String
    external fun parseFontsDirectory(directory: String): String

    // 文件夹选择器回调
    private var onSourceFolderSelected: ((String) -> Unit)? = null
    private var onTargetFolderSelected: ((String) -> Unit)? = null
    private var onParseFolderSelected: ((String) -> Unit)? = null

    // 文件夹选择器启动器
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            val path = getRealPathFromURI(selectedUri) ?: selectedUri.toString()
            when {
                onSourceFolderSelected != null -> {
                    onSourceFolderSelected?.invoke(path)
                    onSourceFolderSelected = null
                }
                onTargetFolderSelected != null -> {
                    onTargetFolderSelected?.invoke(path)
                    onTargetFolderSelected = null
                }
                onParseFolderSelected != null -> {
                    onParseFolderSelected?.invoke(path)
                    onParseFolderSelected = null
                }
            }
        }
    }

    // 尝试从URI获取真实路径
    private fun getRealPathFromURI(uri: Uri): String? {
        // 对于Android 11+，通常需要使用DocumentFile API
        // 这里简化处理，返回URI的路径部分
        return uri.path?.let { path ->
            // 去掉前缀，只保留实际路径
            when {
                path.startsWith("/tree/primary:") -> "/storage/emulated/0/" + path.substring(14)
                path.startsWith("/tree/") -> path.substring(6)
                else -> path
            }
        }
    }

    // 启动文件夹选择器的辅助方法
    fun selectSourceFolder(callback: (String) -> Unit) {
        onSourceFolderSelected = callback
        folderPickerLauncher.launch(null)
    }

    fun selectTargetFolder(callback: (String) -> Unit) {
        onTargetFolderSelected = callback
        folderPickerLauncher.launch(null)
    }

    fun selectParseFolder(callback: (String) -> Unit) {
        onParseFolderSelected = callback
        folderPickerLauncher.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DemoTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        mainActivity = this@MainActivity,
                        onLoadFontsInfoCall = { directory -> loadFontsInfo(directory) },
                        onCopyFontFilesCall = { sourceDir, targetDir, overwrite -> 
                            copyFontFiles(sourceDir, targetDir, overwrite) 
                        },
                        onParseFontsCall = { directory -> parseFontsDirectory(directory) }
                    )
                }
            }
        }
    }
}

// 执行状态枚举
enum class ExecutionState {
    IDLE,      // 空闲状态
    RUNNING    // 执行中
}

// 浮动消息数据类
data class FloatingMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val backgroundColor: Color = Color(0xFFF5F5F5),
    val textColor: Color = Color(0xFF333333),
    val duration: Long = 3000L, // 3秒默认显示时间
    val createdAt: Long = System.currentTimeMillis()
)

// 浮动消息管理器
class FloatingMessageManager {
    private val _messages = mutableStateListOf<FloatingMessage>()
    val messages: List<FloatingMessage> = _messages

    fun showMessage(
        text: String,
        backgroundColor: Color = Color(0xFFF5F5F5),
        textColor: Color = Color(0xFF333333),
        duration: Long = 3000L
    ) {
        val message = FloatingMessage(
            text = text,
            backgroundColor = backgroundColor,
            textColor = textColor,
            duration = duration
        )
        _messages.add(0, message) // 新消息添加到顶部

        // 自动移除消息
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.delay(duration)
            _messages.remove(message)
        }
    }

    fun removeMessage(messageId: String) {
        _messages.removeAll { it.id == messageId }
    }

    fun clear() {
        _messages.clear()
    }
}

// 单个浮动消息组件
@Composable
fun FloatingMessageItem(
    message: FloatingMessage,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    animationDelay: Long = 0L
) {
    var isVisible by remember { mutableStateOf(false) }

    // 进入动画
    LaunchedEffect(message.id) {
        delay(animationDelay)
        isVisible = true
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = tween(300, easing = EaseOutBack)
        ) + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = tween(250)
        ) + fadeOut(animationSpec = tween(250)),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable {
                    isVisible = false
                    // 延迟调用onDismiss以允许退出动画完成
                    kotlinx.coroutines.GlobalScope.launch {
                        kotlinx.coroutines.delay(250)
                        onDismiss()
                    }
                },
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = message.backgroundColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.text,
                    color = message.textColor,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        isVisible = false
                        // 延迟调用onDismiss以允许退出动画完成
                        kotlinx.coroutines.GlobalScope.launch {
                            kotlinx.coroutines.delay(250)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = message.textColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// 浮动消息堆栈容器
@Composable
fun FloatingMessageStack(
    messageManager: FloatingMessageManager,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1000f)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 使用AnimatedContent来实现更流畅的列表动画
            messageManager.messages.forEachIndexed { index, message ->
                key(message.id) {
                    // 为每个消息添加延迟，使其具有层叠效果
                    val animationDelay = index * 50L
                    FloatingMessageItem(
                        message = message,
                        onDismiss = { messageManager.removeMessage(message.id) },
                        animationDelay = animationDelay
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    mainActivity: MainActivity? = null,
    onLoadFontsInfoCall: (String) -> String = { "未提供回调方法" },
    onCopyFontFilesCall: (String, String, Boolean) -> String = { _, _, _ -> "未提供复制回调方法" },
    onParseFontsCall: (String) -> String = { "未提供字体解析回调方法" }
) {
    // 浮动消息管理器
    val messageManager = remember { FloatingMessageManager() }
    var nativeResult by remember { mutableStateOf("") }
    var functionResult by remember { mutableStateOf("") }
    var directoryPath by remember { mutableStateOf("/system/fonts") }
    var showNativeResult by remember { mutableStateOf(false) }
    var showFunctionResult by remember { mutableStateOf(false) }

    // 字体复制功能相关状态
    var sourceFontDirectory by remember { mutableStateOf("/system/fonts") }
    var targetFontDirectory by remember { mutableStateOf("/sdcard/copied_fonts") }
    var overwriteExisting by remember { mutableStateOf(false) }
    var fontCopyResult by remember { mutableStateOf("") }
    var showFontCopyResult by remember { mutableStateOf(false) }

    // 字体解析功能相关状态
    var parseDirectoryPath by remember { mutableStateOf("/system/fonts") }
    var fontParseResult by remember { mutableStateOf("") }
    var showFontParseResult by remember { mutableStateOf(false) }

    // 异步执行状态管理
    var nativeExecutionState by remember { mutableStateOf(ExecutionState.IDLE) }
    var functionExecutionState by remember { mutableStateOf(ExecutionState.IDLE) }
    var fontCopyExecutionState by remember { mutableStateOf(ExecutionState.IDLE) }
    var fontParseExecutionState by remember { mutableStateOf(ExecutionState.IDLE) }


    // 协程作用域和取消令牌
    val coroutineScope = rememberCoroutineScope()
    var nativeJob by remember { mutableStateOf<Job?>(null) }
    var functionJob by remember { mutableStateOf<Job?>(null) }
    var fontCopyJob by remember { mutableStateOf<Job?>(null) }
    var fontParseJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top
        ) {
            // 标题
            Text(
                text = "功能测试",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )


            // 输入框和获取字体信息按钮放在同一行
            // 字体信息目录选择器
            FolderSelectionRow(
                label = "字体目录",
                selectedPath = directoryPath,
                onFolderSelect = { 
                    mainActivity?.selectSourceFolder { path ->
                        directoryPath = path
                    }
                },
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Spacer(modifier = Modifier.weight(1f))

                // 从指定目录获取字体信息按钮
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (nativeExecutionState == ExecutionState.IDLE) {
                                // 开始异步执行
                                nativeExecutionState = ExecutionState.RUNNING

                                nativeJob = coroutineScope.launch {
                                    try {
                                        if (isActive) {
                                            nativeResult = try {
                                                onLoadFontsInfoCall(directoryPath)
                                            } catch (e: Exception) {
                                                "Native调用失败: ${e.message}"
                                            }
                                            showNativeResult = true
                                            nativeExecutionState = ExecutionState.IDLE

                                            // 显示完成提示
                                            messageManager.showMessage(
                                                text = "字体信息获取完成！",
                                                backgroundColor = Color(0xFF4CAF50),
                                                textColor = Color.White,
                                                duration = 3000L
                                            )
                                        }
                                    } catch (e: CancellationException) {
                                        // 操作被取消，只需要还原状态，不显示结果
                                        nativeExecutionState = ExecutionState.IDLE
                                    }
                                }
                            }
                        },
                        enabled = nativeExecutionState == ExecutionState.IDLE,
                        modifier = Modifier.height(56.dp)
                    ) {
                        if (nativeExecutionState == ExecutionState.RUNNING) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                )
                                Text("执行中...")
                            }
                        } else {
                            Text("获取字体信息")
                        }
                    }

                    // 取消按钮
                    if (nativeExecutionState == ExecutionState.RUNNING) {
                        Button(
                            onClick = {
                                nativeJob?.cancel()
                                nativeJob = null
                                // 还原状态，允许再次执行
                                nativeExecutionState = ExecutionState.IDLE

                                // 立即显示取消提醒
                                messageManager.showMessage(
                                    text = "获取字体信息已取消",
                                    backgroundColor = Color(0xFFFF9800),
                                    textColor = Color.White,
                                    duration = 2000L
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.height(56.dp)
                        ) {
                            Text("取消")
                        }
                    }
                }
            }

            // Native结果显示区域
            if (nativeResult.isNotEmpty() && showNativeResult) {
                ResultCard(
                    title = "系统字体信息：",
                    content = nativeResult,
                    modifier = Modifier.padding(bottom = 16.dp),
                    onClose = { showNativeResult = false },
                    messageManager = messageManager
                )
            }

            // 字体复制功能部分
            Text(
                text = "字体文件复制",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp, top = 16.dp)
            )

            // 源目录选择器
            FolderSelectionRow(
                label = "源字体目录",
                selectedPath = sourceFontDirectory,
                onFolderSelect = { 
                    mainActivity?.selectSourceFolder { path ->
                        sourceFontDirectory = path
                    }
                },
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 目标目录选择器
            FolderSelectionRow(
                label = "目标字体目录",
                selectedPath = targetFontDirectory,
                onFolderSelect = { 
                    mainActivity?.selectTargetFolder { path ->
                        targetFontDirectory = path
                    }
                },
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 覆盖已存在文件的选项
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = overwriteExisting,
                    onCheckedChange = { overwriteExisting = it }
                )
                Text(
                    text = "覆盖已存在的文件",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // 开始复制按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (fontCopyExecutionState == ExecutionState.IDLE) {
                            // 开始异步复制
                            fontCopyExecutionState = ExecutionState.RUNNING

                            fontCopyJob = coroutineScope.launch {
                                try {
                                    if (isActive) {
                                        fontCopyResult = try {
                                            onCopyFontFilesCall(sourceFontDirectory, targetFontDirectory, overwriteExisting)
                                        } catch (e: Exception) {
                                            "字体复制失败: ${e.message}"
                                        }
                                        showFontCopyResult = true
                                        fontCopyExecutionState = ExecutionState.IDLE

                                        // 显示完成提示
                                        messageManager.showMessage(
                                            text = "字体文件复制完成！",
                                            backgroundColor = Color(0xFF9C27B0),
                                            textColor = Color.White,
                                            duration = 3000L
                                        )
                                    }
                                } catch (e: CancellationException) {
                                    // 操作被取消，还原状态
                                    fontCopyExecutionState = ExecutionState.IDLE
                                }
                            }
                        }
                    },
                    enabled = fontCopyExecutionState == ExecutionState.IDLE,
                    modifier = Modifier.weight(1f)
                ) {
                    if (fontCopyExecutionState == ExecutionState.RUNNING) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                            )
                            Text("复制中...")
                        }
                    } else {
                        Text("开始复制字体")
                    }
                }

                // 取消按钮
                if (fontCopyExecutionState == ExecutionState.RUNNING) {
                    Button(
                        onClick = {
                            fontCopyJob?.cancel()
                            fontCopyJob = null
                            // 还原状态，允许再次执行
                            fontCopyExecutionState = ExecutionState.IDLE

                            // 显示取消提醒
                            messageManager.showMessage(
                                text = "字体复制已取消",
                                backgroundColor = Color(0xFFFF9800),
                                textColor = Color.White,
                                duration = 2000L
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("取消")
                    }
                }
            }

            // 字体复制结果显示区域
            if (fontCopyResult.isNotEmpty() && showFontCopyResult) {
                ResultCard(
                    title = "字体复制结果：",
                    content = fontCopyResult,
                    modifier = Modifier.padding(bottom = 16.dp),
                    onClose = { showFontCopyResult = false },
                    messageManager = messageManager
                )
            }

            // 字体解析功能部分
            Text(
                text = "字体解析",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp, top = 16.dp)
            )

            // 解析目录选择器
            FolderSelectionRow(
                label = "解析字体目录",
                selectedPath = parseDirectoryPath,
                onFolderSelect = { 
                    mainActivity?.selectParseFolder { path ->
                        parseDirectoryPath = path
                    }
                },
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Spacer(modifier = Modifier.weight(1f))

                // 解析按钮
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (fontParseExecutionState == ExecutionState.IDLE) {
                                // 开始异步解析
                                fontParseExecutionState = ExecutionState.RUNNING

                                fontParseJob = coroutineScope.launch {
                                    try {
                                        if (isActive) {
                                            fontParseResult = try {
                                                onParseFontsCall(parseDirectoryPath)
                                            } catch (e: Exception) {
                                                "字体解析失败: ${e.message}"
                                            }
                                            showFontParseResult = true
                                            fontParseExecutionState = ExecutionState.IDLE

                                            // 显示完成提示
                                            messageManager.showMessage(
                                                text = "字体解析完成！",
                                                backgroundColor = Color(0xFF009688),
                                                textColor = Color.White,
                                                duration = 3000L
                                            )
                                        }
                                    } catch (e: CancellationException) {
                                        // 操作被取消，还原状态
                                        fontParseExecutionState = ExecutionState.IDLE
                                    }
                                }
                            }
                        },
                        enabled = fontParseExecutionState == ExecutionState.IDLE,
                        modifier = Modifier.height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF009688)
                        )
                    ) {
                        if (fontParseExecutionState == ExecutionState.RUNNING) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White
                                )
                                Text("解析中...")
                            }
                        } else {
                            Text("解析字体")
                        }
                    }

                    // 取消按钮
                    if (fontParseExecutionState == ExecutionState.RUNNING) {
                        Button(
                            onClick = {
                                fontParseJob?.cancel()
                                fontParseJob = null
                                // 还原状态，允许再次执行
                                fontParseExecutionState = ExecutionState.IDLE

                                // 显示取消提醒
                                messageManager.showMessage(
                                    text = "字体解析已取消",
                                    backgroundColor = Color(0xFFFF9800),
                                    textColor = Color.White,
                                    duration = 2000L
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.height(56.dp)
                        ) {
                            Text("取消")
                        }
                    }
                }
            }

            // 字体解析结果显示区域
            if (fontParseResult.isNotEmpty() && showFontParseResult) {
                ResultCard(
                    title = "字体解析结果：",
                    content = fontParseResult,
                    modifier = Modifier.padding(bottom = 16.dp),
                    onClose = { showFontParseResult = false },
                    messageManager = messageManager
                )
            }

            // 函数执行按钮
            Text(
                text = "其他功能",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp, top = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (functionExecutionState == ExecutionState.IDLE) {
                            // 开始异步执行
                            functionExecutionState = ExecutionState.RUNNING

                            functionJob = coroutineScope.launch {
                                try {
                                    functionResult = executeLongFunctionAsync()
                                    if (isActive) {
                                        showFunctionResult = true
                                        functionExecutionState = ExecutionState.IDLE

                                        // 显示完成提示
                                        messageManager.showMessage(
                                            text = "函数执行完成！",
                                            backgroundColor = Color(0xFF2196F3),
                                            textColor = Color.White,
                                            duration = 3000L
                                        )
                                    }
                                } catch (e: CancellationException) {
                                    // 函数执行被取消，只需要还原状态，不显示结果
                                    functionExecutionState = ExecutionState.IDLE
                                }
                            }
                        }
                    },
                    enabled = functionExecutionState == ExecutionState.IDLE,
                    modifier = Modifier.weight(1f)
                ) {
                    if (functionExecutionState == ExecutionState.RUNNING) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                            )
                            Text("执行中...")
                        }
                    } else {
                        Text("执行函数")
                    }
                }

                // 取消按钮
                if (functionExecutionState == ExecutionState.RUNNING) {
                    Button(
                        onClick = {
                            functionJob?.cancel()
                            functionJob = null
                            // 还原状态，允许再次执行
                            functionExecutionState = ExecutionState.IDLE

                            // 显示取消提醒
                            messageManager.showMessage(
                                text = "函数执行已取消",
                                backgroundColor = Color(0xFFFF9800),
                                textColor = Color.White,
                                duration = 2000L
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("取消")
                    }
                }
            }

            // 函数结果显示区域
            if (functionResult.isNotEmpty() && showFunctionResult) {
                ResultCard(
                    title = "函数执行结果",
                    content = functionResult,
                    onClose = { showFunctionResult = false },
                    messageManager = messageManager
                )
            }
        }

        // 浮动消息堆栈 - 显示在最顶层
        FloatingMessageStack(
            messageManager = messageManager,
            modifier = Modifier
        )
    }
}

@Composable
fun ResultCard(
    title: String,
    content: String,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    messageManager: FloatingMessageManager? = null
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 标题和操作按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 分享按钮 - 以txt文件方式分享内容
                    TextButton(
                        onClick = {
                            val textLength = content.length
                            
                            // 显示开始分享的提示
                            messageManager?.showMessage(
                                text = "📁 正在创建文本文件 ($textLength 字符)...",
                                backgroundColor = Color(0xFF2196F3),
                                textColor = Color.White,
                                duration = 2000L
                            )
                            
                            // 创建文件标题（根据原标题生成）
                            val fileTitle = when {
                                title.contains("字体信息") -> "字体信息"
                                title.contains("复制结果") -> "字体复制结果"
                                title.contains("解析结果") -> "字体解析结果"
                                title.contains("函数执行") -> "函数执行结果"
                                else -> "应用结果"
                            }
                            
                            // 分享文件
                            val shareSuccess = shareTextFile(context, content, fileTitle)
                            
                            if (shareSuccess) {
                                messageManager?.showMessage(
                                    text = "📤 文件已创建，正在打开分享界面...",
                                    backgroundColor = Color(0xFF4CAF50),
                                    textColor = Color.White,
                                    duration = 3000L
                                )
                            } else {
                                messageManager?.showMessage(
                                    text = "❌ 文件创建失败，请重试",
                                    backgroundColor = Color(0xFFF44336),
                                    textColor = Color.White,
                                    duration = 3000L
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "分享",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // 关闭按钮
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 可滚动的内容区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp, max = 250.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = content, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
        }
    }
}

// 异步版本的长时间执行函数，支持进度回调和取消
suspend fun executeLongFunctionAsync(onProgress: (Float) -> Unit = {}): String =
    withContext(Dispatchers.Default) {
        buildString {
            appendLine("函数执行开始...")
            appendLine("正在初始化参数...")
            appendLine()
            appendLine("执行步骤:")

            val totalSteps = 15
            repeat(totalSteps) { step ->
                // 检查协程是否被取消
                ensureActive()

                // 更新进度
                onProgress((step + 1).toFloat() / totalSteps)

                appendLine(
                    "步骤 ${step + 1}: ${
                        when (step % 4) {
                            0 -> "数据预处理"
                            1 -> "算法计算"
                            2 -> "结果验证"
                            else -> "数据清理"
                        }
                    }"
                )
                appendLine("   - 子任务A: 完成")
                appendLine("   - 子任务B: 完成")
                val taskTime = (Math.random() * 100).toInt()
                appendLine("   - 耗时: ${taskTime}ms")
                appendLine()

                // 模拟每个步骤的执行时间
                delay(300 + (Math.random() * 200).toLong())
            }

            appendLine("最终结果:")
            appendLine("- 处理成功率: 98.5%")
            appendLine("- 总耗时: ${(Math.random() * 5000).toInt()}ms")
            appendLine("- 输出数据量: ${(Math.random() * 10000).toInt()}条记录")
            appendLine()
            appendLine("函数执行完成!")
        }
    }

// 文件夹选择行组件
@Composable
fun FolderSelectionRow(
    label: String,
    selectedPath: String,
    onFolderSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 显示选中路径的卡片
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Text(
                    text = if (selectedPath.isNotEmpty()) selectedPath else "未选择文件夹",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 14.sp,
                    color = if (selectedPath.isNotEmpty()) 
                        MaterialTheme.colorScheme.onSurfaceVariant 
                    else 
                        MaterialTheme.colorScheme.outline,
                    maxLines = 2
                )
            }
            
            // 选择按钮
            Button(
                onClick = onFolderSelect,
                modifier = Modifier.height(48.dp)
            ) {
                Text("选择")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    DemoTheme {
        MainScreen()
    }
}