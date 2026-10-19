package dev.soundlock

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var settingsRevision by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GuardState.current = GuardState.current.copy(armed = GuardState.armed(this))
        setContent { SoundLockTheme { SoundLockScreen(settingsRevision) } }
    }
    override fun onResume() { super.onResume(); settingsRevision++; GuardState.service?.refreshSettings() }
    fun openSettings(action: String, withPackage: Boolean = false) {
        val intent = Intent(action).apply { if (withPackage) data = Uri.parse("package:$packageName") }
        runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    fun openProject() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nina-CP200/SoundLock"))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
        }
    }
    fun notifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
}

@Composable
private fun SoundLockTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF80D4D9), onPrimary = Color(0xFF00363A), primaryContainer = Color(0xFF104C51), onPrimaryContainer = Color(0xFFB5F0F3),
        surface = Color(0xFF101415), onSurface = Color(0xFFDEE4E5), surfaceVariant = Color(0xFF253033), onSurfaceVariant = Color(0xFFBAC8CA),
        outlineVariant = Color(0xFF3C4A4C))
    else lightColorScheme(
        primary = Color(0xFF006970), onPrimary = Color.White, primaryContainer = Color(0xFFD0EFF0), onPrimaryContainer = Color(0xFF004F55),
        surface = Color.White, onSurface = Color(0xFF172123), surfaceVariant = Color(0xFFEDF3F3), onSurfaceVariant = Color(0xFF485C5F),
        outlineVariant = Color(0xFFD1DDDE))
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun SoundLockScreen(settingsRevision: Int) {
    val activity = LocalActivity.current as MainActivity
    val state = GuardState.current
    var page by rememberSaveable { mutableStateOf("home") }
    BackHandler(enabled = page != "home") {
        page = when (page) { "help", "words" -> "about"; else -> "home" }
    }
    var showDisclosure by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.deadline) { while (true) { now = SystemClock.elapsedRealtime(); delay(1_000) } }
    val notificationsAllowed = remember(settingsRevision) { activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val exactAllowed = remember(settingsRevision) { activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }
    val colors = MaterialTheme.colorScheme
    val headline = when {
        !state.connected -> "尚未开启"
        !state.armed -> "保护已暂停"
        state.problem != null -> "保护异常"
        state.route == LockPolicy.Route.UNKNOWN -> "正在检查"
        state.route == LockPolicy.Route.EXTERNAL -> "外接设备使用中"
        state.unlocked -> "外放已解锁"
        else -> "外放已锁定"
    }
    val detail = when {
        !state.connected -> "开启服务，保护媒体外放。"
        !state.armed -> "开启保护后重新上锁。"
        state.problem != null -> state.problem
        state.route == LockPolicy.Route.UNKNOWN -> "暂时保持媒体静音。"
        state.route == LockPolicy.Route.EXTERNAL -> "切回手机后自动上锁。"
        state.unlocked && state.deadline != null -> {
            val seconds = ((state.deadline - now).coerceAtLeast(0) + 999) / 1000
            "%02d:%02d 后自动上锁".format(seconds / 60, seconds % 60)
        }
        state.unlocked -> "播放结束后开始自动上锁计时。"
        else -> "按音量加键，确认后解锁。"
    }
    Scaffold(containerColor = colors.surface) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            // Reset scrolling on navigation so each destination opens at its heading.
            key(page) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (page != "home") {
                            TextButton(onClick = { page = when (page) { "help", "words" -> "about"; else -> "home" } }) { Text("返回") }
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(when (page) {
                            "settings" -> "设置"; "about" -> "关于"; "help" -> "使用说明"
                            "words" -> "我要说的话"; else -> "声锁"
                        }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    when (page) {
                        "home" -> {
                            Spacer(Modifier.height(16.dp))
                            LockSymbol(state.connected && state.armed && state.unlocked, Modifier.size(64.dp))
                            Spacer(Modifier.height(24.dp))
                            Text(headline, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            Text(detail.orEmpty(), style = MaterialTheme.typography.bodyLarge,
                                color = if (state.problem != null) colors.error else colors.onSurfaceVariant)
                            Spacer(Modifier.height(28.dp))
                            when {
                                !state.connected -> Button(onClick = { showDisclosure = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("开启保护") }
                                !state.armed -> Button(onClick = { GuardState.setArmed(activity, true) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("开启保护") }
                                state.unlocked -> Button(onClick = { GuardState.service?.lockNow() }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("立即上锁") }
                                state.route == LockPolicy.Route.SPEAKER -> Button(onClick = { unlockError = null; showUnlock = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("解锁外放") }
                                state.route == LockPolicy.Route.UNKNOWN || state.problem != null -> OutlinedButton(onClick = { GuardState.service?.checkTimer() }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("重新检查") }
                            }
                            Spacer(Modifier.height(40.dp))
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("外放保护", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Switch(checked = state.armed, onCheckedChange = { GuardState.setArmed(activity, it) })
                            }
                            HorizontalDivider(color = colors.outlineVariant)
                            PageRow("设置") { page = "settings" }
                            HorizontalDivider(color = colors.outlineVariant)
                            PageRow("关于") { page = "about" }
                        }
                        "settings" -> {
                            SettingRow("音量保护服务", if (state.connected) "已开启" else "未开启", "管理") { showDisclosure = true }
                            HorizontalDivider(color = colors.outlineVariant)
                            SettingRow("状态通知", if (notificationsAllowed) "已允许" else "未允许", "管理") { activity.notifications() }
                            HorizontalDivider(color = colors.outlineVariant)
                            SettingRow("自动上锁计时", if (exactAllowed) "精确定时已允许" else "精确定时未允许", "设置") { activity.openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) }
                            HorizontalDivider(color = colors.outlineVariant)
                            SettingRow("后台运行", "应用系统设置", "设置") { activity.openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
                        }
                        "about" -> {
                            Text("声锁", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text("版本 " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            Spacer(Modifier.height(32.dp))
                            PageRow("使用说明") { page = "help" }
                            HorizontalDivider(color = colors.outlineVariant)
                            PageRow("我要说的话") { page = "words" }
                            HorizontalDivider(color = colors.outlineVariant)
                            PageRow("GitHub 项目") { activity.openProject() }
                        }
                        "help" -> {
                            HelpSection("锁定与解锁", "锁定后，按音量加键会弹出顶部确认提示，点击「确认解锁」后才能外放。也可以在首页解锁。解锁恢复最低一格音量，不恢复之前的音量。取消或超时后提示会隐藏，平时没有悬浮球。")
                            HelpSection("自动上锁", "连续 15 分钟没有媒体播放后自动上锁；播放期间不计时。媒体音量调回零、耳机断开或保护服务重连时，也会重新上锁。可在系统快捷设置中添加「声锁」，随时上锁。")
                            HelpSection("耳机与通话", "外接设备正常使用，切回手机扬声器后重新上锁。声锁只保护媒体外放，不调整通话、铃声和闹钟音量。")
                            HelpSection("权限与后台", "无障碍服务用于接收音量键和显示确认提示，不读取屏幕内容。状态通知提供上锁入口。精确定时用于息屏后的自动上锁；未允许时可能延迟。ColorOS 中还需留意应用的后台活动和自启动设置。")
                            HelpSection("音量适配", "声锁会自动选择音量控制方式。部分 OPPO 设备的后台媒体音量接口受限，会通过与媒体联动的无障碍音量通道归零。若已有独立读屏音量，则使用标准媒体接口。兼容通道的音量读数只是估计，最低档不代表已确认静音。")
                            HelpSection("使用范围", "普通应用无法保证绝对零漏音。系统或其他应用调高音量后，声锁会尽快归零；服务被停止时无法继续保护。顶部提示由声锁显示，尚未接入 OPPO 原生流体云。")
                            HelpSection("隐私", "本机处理，不申请网络或麦克风权限，不录音，不收集屏幕内容。")
                            Text("当前状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(16.dp))
                            InfoLine("输出设备", when (state.route) { LockPolicy.Route.SPEAKER -> "手机扬声器"; LockPolicy.Route.EXTERNAL -> "外接设备"; else -> "暂未确认" })
                            Spacer(Modifier.height(12.dp))
                            InfoLine("媒体音量", "${if (state.volumeEstimated) "约 " else ""}${state.volume} / ${state.maxVolume}")
                            Spacer(Modifier.height(12.dp))
                            InfoLine("控制方式", state.volumeMode)
                        }
                        "words" -> {
                            Text(stringResource(R.string.about_message).ifBlank { "暂无内容" }, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (showDisclosure) AlertDialog(
        onDismissRequest = { showDisclosure = false },
        title = { Text("开启保护服务") },
        text = { Text("声锁需要无障碍服务来接收音量键并显示解锁提示，不读取屏幕内容或收集输入文字。\n\n请在系统设置中开启「声锁 · 音量保护」。若提示受限设置，请先在应用信息中允许。你可以随时暂停保护或关闭服务。") },
        confirmButton = { TextButton(onClick = { showDisclosure = false; activity.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("前往系统设置") } },
        dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text("暂不开启") } }
    )
    if (showUnlock) AlertDialog(
        onDismissRequest = { showUnlock = false }, title = { Text("解锁外放？") },
        text = { Text(unlockError ?: "恢复最低一格音量，停止播放 15 分钟后自动上锁。") },
        confirmButton = { TextButton(onClick = {
            val service = GuardState.service
            if (service?.confirmUnlock() == true) { showUnlock = false; service.showUnlockFeedback() }
            else unlockError = service?.unlockFailureMessage ?: "保护服务尚未连接，请重新开启服务。"
        }) { Text("确认解锁") } },
        dismissButton = { TextButton(onClick = { showUnlock = false }) { Text("保持静音") } }
    )
}

@Composable
private fun PageRow(title: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        contentPadding = PaddingValues(vertical = 12.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HelpSection(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
@Composable
private fun SettingRow(title: String, detail: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick, Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}
@Composable
private fun LockSymbol(open: Boolean, modifier: Modifier) {
    val progress by animateFloatAsState(if (open) 1f else 0f, label = "lock")
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val u = size.width / 64f
        val stroke = Stroke(3f * u)
        drawRoundRect(color, Offset(12f * u, 30f * u), Size(40f * u, 30f * u), CornerRadius(6f * u), style = stroke)
        rotate(-36f * progress, Offset(20f * u, 30f * u)) {
            drawPath(Path().apply {
                moveTo(20f * u, 30f * u); lineTo(20f * u, 20f * u)
                cubicTo(20f * u, 3f * u, 44f * u, 3f * u, 44f * u, 20f * u)
                lineTo(44f * u, (30f - progress * 6f) * u)
            }, color, style = stroke)
        }
        drawLine(color, Offset(32f * u, 41f * u), Offset(32f * u, 49f * u), 3f * u)
    }
}
