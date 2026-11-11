package dev.soundlock

import android.Manifest
import android.app.AlarmManager
import android.app.LocaleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var settingsRevision by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GuardState.current = GuardState.current.copy(armed = GuardState.armed(this), idleMinutes = GuardState.idleMinutes(this))
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
            Toast.makeText(this, getString(R.string.toast_no_app_for_link), Toast.LENGTH_SHORT).show()
        }
    }
    fun notifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    fun setLanguage(tag: String?) {
        val locales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        getSystemService(LocaleManager::class.java).applicationLocales = locales
    }
    fun launchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                AppEntry(info.activityInfo.packageName, info.loadLabel(packageManager).toString(),
                    info.loadIcon(packageManager).toImageBitmap())
            }
            .sortedBy { it.label.lowercase() }
            .toList()
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
        page = when (page) { "help", "words" -> "about"; "apps" -> "settings"; else -> "home" }
    }
    var showDisclosure by remember { mutableStateOf(false) }
    var showIdleTimeout by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var allowedRevision by remember { mutableIntStateOf(0) }
    var showUnlock by remember { mutableStateOf(false) }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.deadline) { while (true) { now = SystemClock.elapsedRealtime(); delay(1_000) } }
    val notificationsAllowed = remember(settingsRevision) { activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val exactAllowed = remember(settingsRevision) { activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }
    val languageTag = remember(settingsRevision) {
        activity.getSystemService(LocaleManager::class.java).applicationLocales
            .takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
    }
    val languageLabel = stringResource(when {
        languageTag == null -> R.string.language_system
        languageTag.startsWith("zh-Hant") || languageTag.startsWith("zh-TW") ||
            languageTag.startsWith("zh-HK") || languageTag.startsWith("zh-MO") -> R.string.language_zh_hant
        languageTag.startsWith("zh") -> R.string.language_zh_hans
        else -> R.string.language_en
    })
    val allowedPackages = remember(settingsRevision, allowedRevision) { GuardState.allowedPackages(activity) }
    var apps by remember(page) { mutableStateOf(emptyList<AppEntry>()) }
    LaunchedEffect(page) { if (page == "apps") apps = withContext(Dispatchers.IO) { activity.launchableApps() } }
    val colors = MaterialTheme.colorScheme
    val headline = stringResource(when {
        !state.connected -> R.string.status_disconnected_title
        !state.armed -> R.string.status_paused_title
        state.problem != null -> R.string.status_problem_title
        state.route == LockPolicy.Route.UNKNOWN -> R.string.status_checking_title
        state.route == LockPolicy.Route.EXTERNAL -> R.string.status_external_title
        state.allowed -> R.string.status_allowed_title
        state.unlocked -> R.string.status_unlocked_title
        else -> R.string.status_locked_title
    })
    val detail = when {
        !state.connected -> stringResource(R.string.status_disconnected_detail)
        !state.armed -> stringResource(R.string.status_paused_detail)
        state.problem != null -> state.problem
        state.route == LockPolicy.Route.UNKNOWN -> stringResource(R.string.status_checking_detail)
        state.route == LockPolicy.Route.EXTERNAL -> stringResource(R.string.status_external_detail)
        state.allowed -> stringResource(R.string.status_allowed_detail)
        state.unlocked && state.deadline != null -> {
            val seconds = ((state.deadline - now).coerceAtLeast(0) + 999) / 1000
            stringResource(R.string.status_countdown,
                stringResource(R.string.countdown_value, seconds / 60, seconds % 60))
        }
        state.unlocked -> stringResource(R.string.status_unlocked_idle_detail)
        else -> stringResource(R.string.status_locked_detail)
    }
    Scaffold(containerColor = colors.surface) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            // Reset scrolling on navigation so each destination opens at its heading.
            key(page) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (page != "home") {
                            TextButton(onClick = { page = when (page) { "help", "words" -> "about"; "apps" -> "settings"; else -> "home" } }) { Text(stringResource(R.string.action_back)) }
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(when (page) {
                            "settings" -> R.string.page_settings; "about" -> R.string.page_about; "help" -> R.string.page_help
                            "words" -> R.string.page_words; "apps" -> R.string.setting_app_rules; else -> R.string.app_name
                        }), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    when (page) {
                        "home" -> {
                            Spacer(Modifier.height(16.dp))
                            LockSymbol(state.connected && state.armed && (state.unlocked || state.allowed), Modifier.size(64.dp))
                            Spacer(Modifier.height(24.dp))
                            Text(headline, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            Text(detail.orEmpty(), style = MaterialTheme.typography.bodyLarge,
                                color = if (state.problem != null) colors.error else colors.onSurfaceVariant)
                            Spacer(Modifier.height(28.dp))
                            when {
                                !state.connected -> Button(onClick = { showDisclosure = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.action_enable_protection)) }
                                !state.armed -> Button(onClick = { GuardState.setArmed(activity, true) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.action_enable_protection)) }
                                state.unlocked || state.allowed -> Button(onClick = { GuardState.service?.lockNow() }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.action_lock_now)) }
                                state.route == LockPolicy.Route.SPEAKER -> Button(onClick = { unlockError = null; showUnlock = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.action_unlock_speaker)) }
                                state.route == LockPolicy.Route.UNKNOWN || state.problem != null -> OutlinedButton(onClick = { GuardState.service?.checkTimer() }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.action_check_again)) }
                            }
                            Spacer(Modifier.height(40.dp))
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.row_speaker_protection), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Switch(checked = state.armed, onCheckedChange = { GuardState.setArmed(activity, it) })
                            }
                            HorizontalDivider(color = colors.outlineVariant)
                            PageRow(stringResource(R.string.page_settings)) { page = "settings" }
                            HorizontalDivider(color = colors.outlineVariant)
                            PageRow(stringResource(R.string.page_about)) { page = "about" }
                        }
                        "settings" -> {
                            SettingRow(stringResource(R.string.setting_service), stringResource(if (state.connected) R.string.value_on else R.string.value_off), stringResource(R.string.action_manage)) { showDisclosure = true }
                            HorizontalDivider(color = colors.outlineVariant)
                            SettingRow(stringResource(R.string.setting_idle_timeout), stringResource(R.string.minutes_value, state.idleMinutes), stringResource(R.string.action_choose)) { showIdleTimeout = true }
                            HorizontalDivider(color = colors.outlineVariant)
                            SettingRow(stringResource(R.string.setting_app_rules), pluralStringResource(R.plurals.app_rules_count, allowedPackages.size, allowedPackages.size), stringResource(R.string.action_choose)) { page = "apps" }
                            HorizontalDivider(color = colors.outlineVariant)
                            SettingRow(stringResource(R.string.setting_language), languageLabel, stringResource(R.string.action_choose)) { showLanguage = true }
                            HorizontalDivider(color = colors.outlineVariant)
                            SettingRow(stringResource(R.string.setting_notifications), stringResource(if (notificationsAllowed) R.string.value_allowed else R.string.value_not_allowed), stringResource(R.string.action_manage)) { activity.notifications() }
                            HorizontalDivider(color = colors.outlineVariant)
                            SettingRow(stringResource(R.string.setting_exact_alarm), stringResource(if (exactAllowed) R.string.setting_exact_allowed else R.string.setting_exact_not_allowed), stringResource(R.string.action_settings)) { activity.openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) }
                            HorizontalDivider(color = colors.outlineVariant)
                            SettingRow(stringResource(R.string.setting_background), stringResource(R.string.setting_background_detail), stringResource(R.string.action_settings)) { activity.openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
                        }
                        "about" -> {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            Spacer(Modifier.height(32.dp))
                            PageRow(stringResource(R.string.page_help)) { page = "help" }
                            HorizontalDivider(color = colors.outlineVariant)
                            PageRow(stringResource(R.string.page_words)) { page = "words" }
                            HorizontalDivider(color = colors.outlineVariant)
                            PageRow(stringResource(R.string.about_github)) { activity.openProject() }
                        }
                        "help" -> {
                            HelpSection(stringResource(R.string.help_lock_title), stringResource(R.string.help_lock_body))
                            HelpSection(stringResource(R.string.help_auto_lock_title), stringResource(R.string.help_auto_lock_body, pluralStringResource(R.plurals.minutes_phrase, state.idleMinutes, state.idleMinutes)))
                            HelpSection(stringResource(R.string.help_headphones_title), stringResource(R.string.help_headphones_body))
                            HelpSection(stringResource(R.string.help_permissions_title), stringResource(R.string.help_permissions_body))
                            HelpSection(stringResource(R.string.help_volume_title), stringResource(R.string.help_volume_body))
                            HelpSection(stringResource(R.string.help_scope_title), stringResource(R.string.help_scope_body))
                            HelpSection(stringResource(R.string.help_privacy_title), stringResource(R.string.help_privacy_body))
                            Text(stringResource(R.string.help_current_state), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(16.dp))
                            InfoLine(stringResource(R.string.help_output_device), stringResource(when (state.route) {
                                LockPolicy.Route.SPEAKER -> R.string.help_output_speaker
                                LockPolicy.Route.EXTERNAL -> R.string.help_output_external
                                else -> R.string.help_output_unknown
                            }))
                            Spacer(Modifier.height(12.dp))
                            InfoLine(stringResource(R.string.help_media_volume), stringResource(
                                if (state.volumeEstimated) R.string.volume_value_estimated else R.string.volume_value,
                                state.volume, state.maxVolume))
                            Spacer(Modifier.height(12.dp))
                            InfoLine(stringResource(R.string.help_control_mode), stringResource(when (state.volumeMode) {
                                VolumeMode.STANDARD -> R.string.volume_mode_standard
                                VolumeMode.ACCESSIBILITY -> R.string.volume_mode_accessibility
                            }))
                        }
                        "words" -> {
                            Text(stringResource(R.string.about_message), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        }
                        "apps" -> {
                            Text(stringResource(R.string.app_rules_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            if (apps.isEmpty()) {
                                Text(stringResource(R.string.app_rules_empty), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                            }
                            apps.forEach { entry ->
                                AppRuleRow(entry, entry.packageName in allowedPackages) { checked ->
                                    GuardState.setAllowedPackage(activity, entry.packageName, checked)
                                    allowedRevision++
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showDisclosure) AlertDialog(
        onDismissRequest = { showDisclosure = false },
        title = { Text(stringResource(R.string.disclosure_title)) },
        text = { Text(stringResource(R.string.disclosure_body)) },
        confirmButton = { TextButton(onClick = { showDisclosure = false; activity.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text(stringResource(R.string.action_open_system_settings)) } },
        dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text(stringResource(R.string.action_not_now)) } }
    )
    if (showUnlock) AlertDialog(
        onDismissRequest = { showUnlock = false }, title = { Text(stringResource(R.string.unlock_dialog_title)) },
        text = { Text(unlockError ?: stringResource(R.string.unlock_dialog_body, pluralStringResource(R.plurals.minutes_phrase, state.idleMinutes, state.idleMinutes))) },
        confirmButton = { TextButton(onClick = {
            val service = GuardState.service
            if (service?.confirmUnlock() == true) { showUnlock = false; service.showUnlockFeedback() }
            else unlockError = service?.unlockFailureMessage ?: activity.getString(R.string.unlock_service_disconnected)
        }) { Text(stringResource(R.string.action_confirm_unlock)) } },
        dismissButton = { TextButton(onClick = { showUnlock = false }) { Text(stringResource(R.string.action_keep_muted)) } }
    )
    if (showIdleTimeout) AlertDialog(
        onDismissRequest = { showIdleTimeout = false }, title = { Text(stringResource(R.string.idle_timeout_title)) },
        text = {
            val choose: (Int) -> Unit = { GuardState.setIdleMinutes(activity, it); showIdleTimeout = false }
            Column {
                IDLE_MINUTES.forEach { minutes ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { choose(minutes) },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = minutes == state.idleMinutes, onClick = { choose(minutes) })
                        Text(stringResource(R.string.minutes_value, minutes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showIdleTimeout = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
    if (showLanguage) AlertDialog(
        onDismissRequest = { showLanguage = false }, title = { Text(stringResource(R.string.language_dialog_title)) },
        text = {
            val choose: (String?) -> Unit = { activity.setLanguage(it); showLanguage = false }
            Column {
                LANGUAGES.forEach { (tag, labelRes) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { choose(tag) },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = tag == languageTag, onClick = { choose(tag) })
                        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showLanguage = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
}

private val IDLE_MINUTES = listOf(5, 15, 30, 60)
private val LANGUAGES = listOf(
    null to R.string.language_system,
    "zh" to R.string.language_zh_hans,
    "zh-Hant" to R.string.language_zh_hant,
    "en" to R.string.language_en
)

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

data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap?)

@Composable
private fun AppRuleRow(entry: AppEntry, allowed: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        entry.icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.width(16.dp))
        Text(entry.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        Switch(checked = allowed, onCheckedChange = onToggle)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun Drawable.toImageBitmap(): ImageBitmap? = runCatching {
    val width = intrinsicWidth.takeIf { it > 0 } ?: 96
    val height = intrinsicHeight.takeIf { it > 0 } ?: 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, width, height)
    draw(Canvas(bitmap))
    bitmap.asImageBitmap()
}.getOrNull()
