package androidx.appcompat.demo

import android.os.Bundle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.appcompat.demo.ui.theme.DemoTheme
import kotlinx.coroutines.*
import java.util.UUID

class MainActivity : ComponentActivity() {

    init {
        // 加载native库
        System.loadLibrary("demo")
    }

    // 声明native方法  
    external fun loadFontsInfo(directory: String): String

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
                        onLoadFontsInfoCall = { directory -> loadFontsInfo(directory) }
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
    onLoadFontsInfoCall: (String) -> String = { "未提供回调方法" }
) {
    // 浮动消息管理器
    val messageManager = remember { FloatingMessageManager() }
    var nativeResult by remember { mutableStateOf("") }
    var functionResult by remember { mutableStateOf("") }
    var directoryPath by remember { mutableStateOf("/system/fonts") }
    var showNativeResult by remember { mutableStateOf(false) }
    var showFunctionResult by remember { mutableStateOf(false) }

    // 异步执行状态管理
    var nativeExecutionState by remember { mutableStateOf(ExecutionState.IDLE) }
    var functionExecutionState by remember { mutableStateOf(ExecutionState.IDLE) }
    var currentProgress by remember { mutableStateOf(0f) }

    // 协程作用域和取消令牌
    val coroutineScope = rememberCoroutineScope()
    var nativeJob by remember { mutableStateOf<Job?>(null) }
    var functionJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // 目录输入框
                OutlinedTextField(
                    value = directoryPath,
                    onValueChange = { directoryPath = it },
                    label = { Text("字体目录路径") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

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
                    onClose = { showNativeResult = false }
                )
            }

            // 函数执行按钮
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
                    onClose = { showFunctionResult = false }
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
    onClose: () -> Unit = {}
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 标题和关闭按钮行
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

// 模拟native代码返回的长内容
fun generateLongNativeContent(): String {
    return buildString {
        appendLine("Native代码执行成功!")
        appendLine("系统信息:")
        appendLine("- CPU架构: ARM64")
        appendLine("- 内存使用: 256MB")
        appendLine("- 可用存储: 8.5GB")
        appendLine()
        appendLine("详细日志:")
        repeat(20) { i ->
            appendLine("[$i] Native函数调用 - 时间戳: ${System.currentTimeMillis()}")
            appendLine("    处理数据块 $i, 大小: ${(Math.random() * 1000).toInt()}KB")
            appendLine("    状态: 成功")
            appendLine()
        }
        appendLine("Native代码执行完成!")
    }
}

// 模拟执行一个返回长内容的函数
fun executeLongFunction(): String {
    return buildString {
        appendLine("函数执行开始...")
        appendLine("正在初始化参数...")
        appendLine()
        appendLine("执行步骤:")

        repeat(15) { step ->
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
            appendLine("   - 耗时: ${(Math.random() * 100).toInt()}ms")
            appendLine()
        }

        appendLine("最终结果:")
        appendLine("- 处理成功率: 98.5%")
        appendLine("- 总耗时: ${(Math.random() * 5000).toInt()}ms")
        appendLine("- 输出数据量: ${(Math.random() * 10000).toInt()}条记录")
        appendLine()
        appendLine("函数执行完成!")
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

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    DemoTheme {
        MainScreen()
    }
}