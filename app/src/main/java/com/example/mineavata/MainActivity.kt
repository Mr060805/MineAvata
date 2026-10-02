package com.example.mineavata

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.mineavata.pet.ImportManager
import com.example.mineavata.pet.ModelAutoMapper
import com.example.mineavata.pet.ModelStorage
import com.example.mineavata.pet.PetService
import com.example.mineavata.ui.theme.MineAvataTheme
import com.example.mineavata.voice.VoiceWakeService
import com.example.mineavata.voice.WakePrefs

class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)
    private var petOn by mutableStateOf(false)
    private var showSettingsDialog by mutableStateOf(false)
    private var showSizeDialog by mutableStateOf(false)
    private var showDetailDialog by mutableStateOf(false)
    private var showWakeWordDialog by mutableStateOf(false)
    private var showManageScreen by mutableStateOf(false)
    private var detailModel by mutableStateOf<ModelInfo?>(null)

    // 弹窗统一灰色底（白色太亮眼）
    private val dialogBgColor = Color(0xFF2E2E2E)

    // 管理页数据
    private var models by mutableStateOf<List<ModelInfo>>(emptyList())

    /** 列表项信息：目录名 + 能力统计（具体清单只在详情弹窗里展开） */
    data class ModelInfo(
        val dir: String,
        val inUse: Boolean,
        val valid: Boolean,
        val motionGroups: Map<String, List<String>>,
        val expressions: List<String>,
    ) {
        val motionCount: Int get() = motionGroups.values.sumOf { it.size }
        val expressionCount: Int get() = expressions.size
    }

    private val zipPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        // 导入交给 ImportManager 后台流水线（拷贝→检查→解压→校验→贴图→安装），
        // 进度对话框观察 StateFlow；旧版同步导入会把主线程卡到黑屏/ANR
        if (uri != null) {
            if (ImportManager.busy) toast("已有导入进行中")
            else ImportManager.start(this, uri)
        }
    }

    /** 麦克风权限：授权才启动语音监听，拒绝则把开关回写关掉（不能停在"开"却什么都没发生） */
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            requestNotificationPermissionIfNeeded()
            VoiceWakeService.setListening(this, true)
            toast("语音唤醒已开启")
        } else {
            WakePrefs.setEnabled(this, false)
            toast("需要麦克风权限才能监听唤醒词")
        }
    }

    /** 通知权限：Android 13+ 才需要；拒绝了监听照样能跑，只是看不到常驻通知 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不影响功能 */ }

    /** manifest 里早已声明 POST_NOTIFICATIONS，但从未运行时申请过（Android 13+ 不申请就不显示） */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        overlayGranted = Settings.canDrawOverlays(this)
        setContent {
            MineAvataTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (showManageScreen) {
                        ManageScreen(
                            models = models,
                            onBack = { showManageScreen = false },
                            onAdd = {
                                if (ImportManager.busy) toast("已有导入进行中")
                                else zipPicker.launch("*/*")
                            },
                            onUse = { useModel(it) },
                            onDelete = { deleteModel(it) },
                            onOpenDetail = { detailModel = it },
                        )
                    } else {
                        HomeScreen(
                            overlayGranted = overlayGranted,
                            petOn = petOn,
                            onToggle = { togglePet() },
                            onOpenSettings = { showSettingsDialog = true },
                        )
                    }
                }
            }
            if (showSettingsDialog) {
                SettingsDialog(
                    onDismiss = { showSettingsDialog = false },
                    onManage = {
                        showSettingsDialog = false
                        refreshModels()
                        showManageScreen = true
                    },
                    onResize = {
                        showSettingsDialog = false
                        showSizeDialog = true
                    },
                    onDetail = {
                        showSettingsDialog = false
                        showDetailDialog = true
                    },
                    onWakeWord = {
                        showSettingsDialog = false
                        showWakeWordDialog = true
                    },
                )
            }
            if (showSizeDialog) {
                SizeDialog(onDismiss = { showSizeDialog = false })
            }
            if (showWakeWordDialog) {
                WakeWordDialog(onDismiss = { showWakeWordDialog = false })
            }
            if (showDetailDialog) {
                DetailDialog(onDismiss = { showDetailDialog = false })
            }
            detailModel?.let { info ->
                DetailDialog(info = info, onDismiss = { detailModel = null })
            }
            // 导入进度对话框：状态在应用级单例里，任何页面都可见；
            // 旋转/进程重建后 collectAsState 自动重放当前进度，对话框自己回来
            val importState by ImportManager.state.collectAsState()
            ImportProgressDialog(importState)
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
        petOn = PetService.isRunning
        // microphone 前台服务无法从后台自启，被系统杀掉后只能在这里恢复
        // （顺便处理权限被撤销的情况：会把开关回写关掉）
        VoiceWakeService.ensureRunning(this)
    }

    private fun togglePet() {
        if (!Settings.canDrawOverlays(this)) {
            toast("需要悬浮窗权限")
            return
        }
        if (petOn) {
            PetService.stop(this)
        } else {
            PetService.start(this)
        }
        // 服务启停是异步的；稍后按真实状态回填（同时本地先乐观翻转保证按钮不卡手）
        petOn = !petOn
        window.decorView.postDelayed({ petOn = PetService.isRunning }, 800)
    }

    private fun refreshModels() {
        ModelStorage.ensureBuiltinModels(this)
        val current = getSharedPreferences("pet_prefs", MODE_PRIVATE).getString("model", null) ?: ""
        models = ModelStorage.scan(this).map { dir ->
            val mapping = ModelAutoMapper.map(this, ModelStorage.modelsRoot(this).resolve(dir))
            if (mapping == null) {
                ModelInfo(
                    dir = dir,
                    inUse = dir == current,
                    valid = false,
                    motionGroups = emptyMap(),
                    expressions = emptyList(),
                )
            } else {
                ModelInfo(
                    dir = dir,
                    inUse = dir == current,
                    valid = true,
                    motionGroups = mapping.motions,
                    expressions = mapping.expressions,
                )
            }
        }
    }

    private fun useModel(dir: String) {
        // 先写 prefs（PetService.start 异步，直接写保证 UI 立即反映"使用中"）
        getSharedPreferences("pet_prefs", MODE_PRIVATE).edit().putString("model", dir).apply()
        PetService.start(this, dir)
        toast("已切换为 $dir")
        refreshModels()
    }

    private fun deleteModel(dir: String) {
        // 至少保留一个宠物模型：只剩一个时禁止删除
        if (ModelStorage.scan(this).size <= 1) {
            toast("至少保留一个宠物模型，无法删除")
            return
        }
        if (ModelStorage.isModelInUse(this, dir)) {
            PetService.stop(this)
            petOn = false
        }
        ModelStorage.delete(this, dir)
        toast("已删除 $dir")
        refreshModels()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- 导入进度对话框（ImportManager 状态机驱动） ----------
    @Composable
    private fun ImportProgressDialog(state: ImportManager.State) {
        // 终态处理：成功 → toast + 刷新列表 + 自动关；取消 → 自动关；失败 → 对话框里展示原因等确认
        LaunchedEffect(state) {
            when (state) {
                is ImportManager.State.Success -> {
                    toast("导入成功: ${state.name}")
                    refreshModels()
                    ImportManager.consumeTerminal()
                }
                ImportManager.State.Cancelled -> ImportManager.consumeTerminal()
                else -> Unit
            }
        }
        if (state is ImportManager.State.Idle ||
            state is ImportManager.State.Success ||
            state is ImportManager.State.Cancelled
        ) return

        val isFailure = state is ImportManager.State.Failure
        val title: String
        val detail: String
        val fraction: Float?
        when (state) {
            is ImportManager.State.Copying -> {
                title = "正在拷贝压缩包"
                detail = if (state.total > 0) "${mb(state.bytes)} / ${mb(state.total)}" else "已拷贝 ${mb(state.bytes)}"
                fraction = if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else null
            }
            ImportManager.State.Inspecting -> {
                title = "正在检查压缩包"
                detail = "校验格式、条目编码与存储空间…"
                fraction = null
            }
            is ImportManager.State.Extracting -> {
                title = "正在解压模型"
                val bytesText = if (state.totalBytes > 0) "${mb(state.bytes)} / ${mb(state.totalBytes)}" else mb(state.bytes)
                detail = "${state.fileIndex}/${state.fileCount} · $bytesText" +
                    (if (state.current.isNotEmpty()) " · ${state.current}" else "")
                fraction = when {
                    state.totalBytes > 0 -> (state.bytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                    state.fileCount > 0 -> (state.fileIndex.toFloat() / state.fileCount).coerceIn(0f, 1f)
                    else -> null
                }
            }
            ImportManager.State.Validating -> {
                title = "正在校验模型"
                detail = "定位 model3.json 并检查文件引用…"
                fraction = null
            }
            is ImportManager.State.Textures -> {
                title = "正在优化贴图"
                detail = if (state.total > 0) {
                    "${state.done}/${state.total}" + (if (state.current.isNotEmpty()) " · ${state.current}" else "")
                } else "检查贴图尺寸…"
                fraction = if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else null
            }
            ImportManager.State.Installing -> {
                title = "正在安装"
                detail = "移入模型目录，即将完成…"
                fraction = null
            }
            is ImportManager.State.Failure -> {
                title = "导入失败"
                detail = state.reason
                fraction = null
            }
            else -> return
        }

        AlertDialog(
            onDismissRequest = {}, // 返回键/点外面不关：取消走按钮，失败确认走"好"
            containerColor = dialogBgColor,
            titleContentColor = Color.White,
            textContentColor = Color(0xFFDDDDDD),
            title = { Text(title) },
            text = {
                Column {
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            },
            confirmButton = {
                if (isFailure) {
                    TextButton(onClick = { ImportManager.consumeTerminal() }) { Text("好") }
                } else {
                    TextButton(onClick = { ImportManager.cancel() }) { Text("取消导入") }
                }
            },
            modifier = Modifier.widthIn(min = 320.dp),
        )
    }

    private fun mb(bytes: Long): String = String.format("%.1fMB", bytes / 1048576f)

    // ---------- 主页 ----------
    @Composable
    private fun HomeScreen(
        overlayGranted: Boolean,
        petOn: Boolean,
        onToggle: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 右上角设置按钮（下移 10dp：top 12 → 22）
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 22.dp, end = 12.dp),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }

            // 屏幕中央：标题在上，按钮紧跟在下（未授权 → 授权按钮；已授权 → 开关按钮）
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("桌面宠物", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                if (!overlayGranted) {
                    Button(
                        onClick = {
                            startActivity(
                                android.content.Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName"),
                                ),
                            )
                        },
                        modifier = Modifier.widthIn(min = 160.dp),
                    ) { Text("授予悬浮窗权限") }
                } else {
                    // 按钮式开关：未开启 = 鲜艳浅紫（点击开启）；开启 = 灰色（再点一次关闭）
                    Button(
                        onClick = onToggle,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (petOn) Color(0xFF9E9E9E) else Color(0xFFB388FF),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.widthIn(min = 160.dp),
                    ) {
                        Text(
                            if (petOn) "关闭" else "开启",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }

    // ---------- 设置弹窗（灰色底，三个按钮） ----------
    @Composable
    private fun SettingsDialog(
        onDismiss: () -> Unit,
        onManage: () -> Unit,
        onResize: () -> Unit,
        onDetail: () -> Unit,
        onWakeWord: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = dialogBgColor,
            titleContentColor = Color.White,
            title = { Text("设置") },
            text = {
                Column {
                    Button(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                        Text("管理宠物")
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onResize, modifier = Modifier.fillMaxWidth()) {
                        Text("调节大小")
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onDetail, modifier = Modifier.fillMaxWidth()) {
                        Text("细节程度")
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onWakeWord, modifier = Modifier.fillMaxWidth()) {
                        Text("唤醒提示词")
                    }
                }
            },
            confirmButton = {},
            modifier = Modifier.widthIn(min = 320.dp),
        )
    }

    // ---------- 细节程度（底部弹窗：滑杆三档，档 0=2048 / 1=4096 默认 / 2=原始） ----------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DetailDialog(onDismiss: () -> Unit) {
        val options = listOf(2048, 4096, 0)
        val stored = ModelStorage.getTextureLimit(this@MainActivity)
        var idx by remember {
            mutableStateOf(options.indexOf(stored).let { if (it < 0) 1 else it })
        }
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = dialogBgColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "细节程度",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (val v = options[idx]) {
                        0 -> "原始尺寸（贴图多大用多大）"
                        else -> "上限 ${v}×${v}（超过的自动压缩）"
                    },
                    color = Color(0xFFCCCCCC),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = idx.toFloat(),
                    onValueChange = { idx = kotlin.math.round(it).toInt().coerceIn(0, 2) },
                    valueRange = 0f..2f,
                    steps = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("2048", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelMedium)
                    Text("4096", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelMedium)
                    Text("原始", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    "改变对之后导入的模型和内置重新释放时生效",
                    color = Color(0xFF999999),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        ModelStorage.setTextureLimit(this@MainActivity, options[idx])
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("确定") }
            }
        }
    }

    // ---------- 调节大小（底部弹窗：左右拖动滑杆 → 确定后重载宠物） ----------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SizeDialog(onDismiss: () -> Unit) {
        var sizeRatio by remember { mutableStateOf(PetService.getSize(this@MainActivity)) }
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = dialogBgColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "调节大小",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = sizeRatio,
                    onValueChange = { sizeRatio = it },
                    valueRange = 0.25f..0.75f,
                    steps = 9,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                )
                Text("${(sizeRatio * 100).toInt()}%", color = Color(0xFFCCCCCC))
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        // 写入新尺寸并重启服务 → 宠物按新大小重载
                        PetService.setPetSize(this@MainActivity, sizeRatio)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("确定") }
            }
        }
    }

    // ---------- 唤醒提示词（底部弹窗：输入框 + 语音监听总开关 + 确定） ----------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun WakeWordDialog(onDismiss: () -> Unit) {
        var phrase by remember { mutableStateOf(WakePrefs.getPhrase(this@MainActivity)) }
        // 开关是"整条语音监听"的总开关，与桌宠开关无关
        var listening by remember { mutableStateOf(WakePrefs.isEnabled(this@MainActivity)) }
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = dialogBgColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "唤醒提示词",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "说话内容里出现这个词时，宠物会随机做一个动作或表情",
                    color = Color(0xFF999999),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    singleLine = true,
                    placeholder = { Text("例如：你好麦麦") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "语音监听",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = listening, onCheckedChange = { listening = it })
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        val p = phrase.trim()
                        WakePrefs.setPhrase(this@MainActivity, p)
                        when {
                            // 没词可匹配，开着只是白耗电
                            listening && p.isEmpty() -> {
                                WakePrefs.setEnabled(this@MainActivity, false)
                                toast("请先填写唤醒提示词")
                            }

                            listening -> {
                                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    requestNotificationPermissionIfNeeded()
                                    VoiceWakeService.setListening(this@MainActivity, true)
                                    toast("语音唤醒已开启")
                                } else {
                                    // 权限结果回调里再启动
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }

                            else -> {
                                VoiceWakeService.setListening(this@MainActivity, false)
                                toast("语音唤醒已关闭")
                            }
                        }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("确定") }
            }
        }
    }

    // ---------- 模型能力详情弹窗（点列表气泡弹出，展开具体清单） ----------
    @Composable
    private fun DetailDialog(info: ModelInfo, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = dialogBgColor,
            titleContentColor = Color.White,
            textContentColor = Color(0xFFDDDDDD),
            title = { Text(displayName(info.dir)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (!info.valid) {
                        Text("解析失败（缺 model3.json）")
                        return@Column
                    }
                    if (info.motionCount == 0 && info.expressionCount == 0) {
                        Text("无动作/表情（纯注视模型）")
                    }
                    info.motionGroups.forEach { (group, files) ->
                        Text(
                            group,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Text(
                            files.joinToString("、") {
                                it.substringAfterLast('/').removeSuffix(".motion3.json")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (info.expressions.isNotEmpty()) {
                        Text(
                            "表情",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Text(
                            info.expressions.joinToString("、"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            },
            modifier = Modifier.widthIn(min = 320.dp),
        )
    }

    // ---------- 管理子页 ----------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ManageScreen(
        models: List<ModelInfo>,
        onBack: () -> Unit,
        onAdd: () -> Unit,
        onUse: (String) -> Unit,
        onDelete: (String) -> Unit,
        onOpenDetail: (ModelInfo) -> Unit,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("管理宠物") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = onAdd) {
                            Icon(Icons.Filled.Add, contentDescription = "添加")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(models, key = { it.dir }) { info ->
                    ModelCard(
                        info = info,
                        onOpenDetail = { onOpenDetail(info) },
                        onUse = { onUse(info.dir) },
                        onDelete = { onDelete(info.dir) },
                    )
                }
            }
        }
    }

    @Composable
    private fun ModelCard(
        info: ModelInfo,
        onOpenDetail: () -> Unit,
        onUse: () -> Unit,
        onDelete: () -> Unit,
    ) {
        // 必须用 remember：局部 mutableStateOf 不 remember 的话，
        // 每次重组都会重置回 false，弹窗永远弹不出来
        var actionOpen by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDetail),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(displayName(info.dir), style = MaterialTheme.typography.titleMedium)
                        if (info.inUse) {
                            Text(
                                " 使用中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    // 副标题只给统计数，具体清单在弹窗里看
                    Text(
                        if (info.valid) "动作 ${info.motionCount} 个 · 表情 ${info.expressionCount} 个"
                        else "解析失败（缺 model3.json）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                IconButton(onClick = { actionOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
            }
        }

        // 操作弹窗：居中、灰色，两个按钮（上“使用”，下“删除”）
        if (actionOpen) {
            AlertDialog(
                onDismissRequest = { actionOpen = false },
                containerColor = dialogBgColor,
                titleContentColor = Color.White,
                title = { Text(displayName(info.dir)) },
                text = {
                    Column {
                        Button(
                            onClick = { actionOpen = false; onUse() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("使用") }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { actionOpen = false; confirmDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("删除") }
                    }
                },
                confirmButton = {},
                modifier = Modifier.widthIn(min = 320.dp),
            )
        }

        // 删除确认弹窗：确定 / 取消
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                containerColor = dialogBgColor,
                titleContentColor = Color.White,
                textContentColor = Color(0xFFDDDDDD),
                title = { Text("确定删除吗") },
                text = { Text("将删除「${displayName(info.dir)}」，删除后需要重新导入") },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                },
                modifier = Modifier.widthIn(min = 320.dp),
            )
        }
    }

    private fun displayName(dir: String): String = when (dir) {
        "xuexiong" -> "雪熊少女"
        "huohuo" -> "霍霍"
        else -> dir
    }
}
