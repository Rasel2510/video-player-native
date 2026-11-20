package com.ayesha.player.ui.vault

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.data.model.VideoFile
import com.ayesha.player.data.vault.VaultAutoLock
import com.ayesha.player.data.vault.VaultRepository
import com.ayesha.player.data.vault.VaultRules
import com.ayesha.player.data.vault.VaultSecurity
import com.ayesha.player.ui.common.AppBottomSheet
import com.ayesha.player.ui.common.ConfirmDialog
import com.ayesha.player.ui.common.OptionRow
import com.ayesha.player.ui.folder.SortOption
import com.ayesha.player.ui.folder.VideoCard
import com.ayesha.player.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val vault: VaultRepository,
    private val security: VaultSecurity,
) : ViewModel() {
    val videos = vault.videos

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    var selectionMode by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Elapsed-realtime when the app went to the background; null when not pending. */
    private var backgroundedAt: Long? = null

    init {
        viewModelScope.launch { vault.load() }
    }

    fun enterSelection(initial: VideoFile? = null) {
        selectionMode = true
        if (initial != null) selected = selected + initial.path
    }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    fun toggle(v: VideoFile) {
        selected = if (v.path in selected) selected - v.path else selected + v.path
    }

    fun selectAll() {
        val all = videos.value.map { it.path }.toSet()
        selected = if (selected.size == all.size) emptySet() else all
    }

    fun restore(list: List<VideoFile>) {
        // Leave selection mode right away: the restore includes a library rescan.
        exitSelection()
        viewModelScope.launch {
            val n = vault.restore(list)
            _messages.send(if (list.size == 1 && n == 1) "Video restored successfully" else "$n video(s) restored")
        }
    }

    fun delete(list: List<VideoFile>) {
        exitSelection()
        viewModelScope.launch {
            val n = vault.delete(list)
            if (n < list.size) _messages.send("Couldn't delete ${list.size - n} video(s)")
        }
    }

    fun selectedVideos() = videos.value.filter { it.path in selected }

    suspend fun autoLockSeconds() = security.autoLockSeconds()

    fun setAutoLock(seconds: Int, current: Int) {
        if (seconds == current) return
        viewModelScope.launch {
            security.setAutoLockSeconds(seconds)
            _messages.send("Auto-lock set to ${VaultRules.formatAutoLock(seconds).lowercase()}")
        }
    }

    fun pinChanged() {
        viewModelScope.launch { _messages.send("Vault PIN updated") }
    }

    fun onBackground() {
        if (backgroundedAt == null) backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** True when the vault should lock now (backgrounded at least the auto-lock time). */
    suspend fun shouldLockOnResume(): Boolean {
        val since = backgroundedAt ?: return false
        backgroundedAt = null
        val limit = security.autoLockSeconds()
        if (limit < 0) return false
        return (SystemClock.elapsedRealtime() - since) / 1000 >= limit
    }
}

private sealed interface VaultDialog {
    data object Menu : VaultDialog
    data class AutoLock(val current: Int) : VaultDialog
    data class Options(val video: VideoFile) : VaultDialog
    data class Delete(val videos: List<VideoFile>) : VaultDialog
}

/** vault_screen.dart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onOpenPlayer: (Route.Player) -> Unit,
    onChangePin: () -> Unit,
    pinChanged: Boolean,
    onPinChangedConsumed: () -> Unit,
    vm: VaultViewModel = hiltViewModel(),
) {
    SecureScreen()
    val c = AppTheme.colors
    val videos by vm.videos.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<VaultDialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(pinChanged) {
        if (pinChanged) {
            vm.pinChanged()
            onPinChangedConsumed()
        }
    }
    // Auto-lock: back from the background after the configured time -> leave the vault.
    // Watches the activity (= the app, single activity), not this screen's own lifecycle: opening a
    // video from the vault must not count as "backgrounded". And like the Flutter build, it only
    // acts while the vault itself is showing (this effect leaves composition when the player opens).
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onBackground()
                Lifecycle.Event.ON_START -> scope.launch {
                    if (vm.shouldLockOnResume()) {
                        dialog = null
                        onBack()
                    }
                }
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    fun open(v: VideoFile) = onOpenPlayer(Route.Player(v.path, v.name, null, Route.VAULT_PLAYLIST))

    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                // Flutter's AppBar: 56 dp tall (kToolbarHeight), 22 dp icons. Compose defaults are 64 / 24.
                expandedHeight = 56.dp,
                navigationIcon = {
                    IconButton(onClick = if (vm.selectionMode) vm::exitSelection else onBack) {
                        Icon(if (vm.selectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(22.dp), tint = c.textPrimary)
                    }
                },
                title = {
                    Text(if (vm.selectionMode) "${vm.selected.size} selected" else "Secure Vault", color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.W600)
                },
                actions = {
                    if (vm.selectionMode) {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Rounded.SelectAll, "Select all", Modifier.size(22.dp), tint = c.textPrimary) }
                    } else {
                        if (videos.isNotEmpty()) {
                            IconButton(onClick = { vm.enterSelection() }) { Icon(Icons.Rounded.Checklist, "Select", Modifier.size(22.dp), tint = c.textPrimary) }
                        }
                        IconButton(onClick = { dialog = VaultDialog.Menu }) { Icon(Icons.Rounded.MoreVert, "Vault settings", Modifier.size(22.dp), tint = c.textPrimary) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg, scrolledContainerColor = c.bg),
            )
        },
        bottomBar = {
            AnimatedVisibility(vm.selectionMode, enter = expandVertically(), exit = shrinkVertically()) {
                Surface(color = c.surface) {
                    Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        val none = vm.selected.isEmpty()
                        Button(
                            onClick = { vm.restore(vm.selectedVideos()) },
                            enabled = !none,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(vertical = 14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg, disabledContainerColor = c.divider, disabledContentColor = c.textMuted),
                        ) {
                            Icon(Icons.Rounded.Restore, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Restore (${vm.selected.size})", fontWeight = FontWeight.W600, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = { dialog = VaultDialog.Delete(vm.selectedVideos()) },
                            enabled = !none,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(vertical = 14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252), contentColor = Color.White, disabledContainerColor = c.divider, disabledContentColor = c.textMuted),
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete", fontWeight = FontWeight.W600, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (videos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(64.dp), tint = c.textMuted)
                    Spacer(Modifier.height(16.dp))
                    Text("Vault is empty", color = c.textSecondary, fontSize = 16.sp)
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 20.dp),
            ) {
                items(videos, key = { it.path }) { v ->
                    VideoCard(
                        video = v,
                        savedPos = null,
                        totalDur = null,
                        isNew = false,
                        sortBy = SortOption.Name,
                        selectionMode = vm.selectionMode,
                        isSelected = v.path in vm.selected,
                        onClick = { open(v) },
                        onLongClick = { dialog = VaultDialog.Options(v) },
                        onSelectToggle = { vm.toggle(v) },
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        VaultDialog.Menu -> AppBottomSheet({ if (dialog == VaultDialog.Menu) dialog = null }) { closer ->
            Text("Vault settings", fontSize = 14.sp, fontWeight = FontWeight.W600, color = c.textPrimary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
            HorizontalDivider(color = c.divider)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                OptionRow(Icons.Rounded.LockReset, "Change PIN", { closer.close(onChangePin) })
                OptionRow(Icons.Outlined.Timer, "Auto-lock timer", {
                    closer.close { scope.launch { dialog = VaultDialog.AutoLock(vm.autoLockSeconds()) } }
                })
            }
        }
        is VaultDialog.AutoLock -> AutoLockSheet(d.current, onPick = { vm.setAutoLock(it, d.current) }) { if (dialog == d) dialog = null }
        is VaultDialog.Options -> AppBottomSheet({ if (dialog == d) dialog = null }) { closer ->
            Text(d.video.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.W600, color = c.textPrimary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
            HorizontalDivider(color = c.divider)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                OptionRow(Icons.Rounded.PlayArrow, "Play", { closer.close { open(d.video) } })
                OptionRow(Icons.Rounded.Restore, "Restore from Vault", { closer.close { vm.restore(listOf(d.video)) } }, color = c.accent)
                OptionRow(Icons.Outlined.CheckCircleOutline, "Select", { closer.close { vm.enterSelection(d.video) } })
                OptionRow(Icons.Rounded.DeleteOutline, "Delete permanently", { closer.close { dialog = VaultDialog.Delete(listOf(d.video)) } }, color = c.errorRed)
            }
        }
        is VaultDialog.Delete -> ConfirmDialog(
            title = "Delete ${d.videos.size} video(s)?",
            text = "These videos will be permanently deleted. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                vm.delete(d.videos)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

/** vault_auto_lock_sheet.dart: presets plus a custom number of minutes. */
@Composable
private fun AutoLockSheet(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    var custom by remember { mutableIntStateOf(if (current > 0) Math.round(current / 60f) else 0) }
    AppBottomSheet(onDismiss) { closer ->
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Timer, null, Modifier.size(18.dp), tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("Auto-lock vault", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
        }
        Text("Re-locks the vault after it has been in the background this long", fontSize = 12.sp, color = c.textMuted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
        HorizontalDivider(color = c.divider)
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp), tint = c.textMuted)
            Spacer(Modifier.width(14.dp))
            Text("Custom", Modifier.weight(1f), color = c.textSecondary, fontSize = 13.sp)
            Step(Icons.Rounded.Remove) { if (custom > 0) custom-- }
            Text(if (custom == 0) "Immediate" else "${custom}m", Modifier.width(68.dp), textAlign = TextAlign.Center, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.W600, fontFamily = FontFamily.Monospace)
            Step(Icons.Rounded.Add) { custom++ }
            Spacer(Modifier.width(16.dp))
            Text(
                "SET",
                Modifier.clip(RoundedCornerShape(6.dp)).background(c.accent).clickable { closer.close { onPick(custom * 60) } }.padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
            )
        }
        HorizontalDivider(color = c.divider)
        VaultAutoLock.entries.forEach { d ->
            val on = d.seconds == current
            Row(Modifier.fillMaxWidth().clickable { closer.close { onPick(d.seconds) } }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Timer, null, Modifier.size(18.dp), tint = if (on) c.accent else c.textMuted)
                Spacer(Modifier.width(14.dp))
                Text(d.label, Modifier.weight(1f), color = if (on) c.accent else c.textSecondary, fontWeight = if (on) FontWeight.W600 else FontWeight.Normal, fontSize = 13.sp)
                if (on) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = c.accent)
            }
        }
    }
}

@Composable
private fun Step(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.elevated).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(18.dp), tint = c.textSecondary) }
}
