package com.ayesha.player.ui.picker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.data.library.LibraryRepository
import com.ayesha.player.ui.library.FolderCard
import com.ayesha.player.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val library: LibraryRepository,
) : ViewModel() {
    val args = savedState.toRoute<Route.FolderPicker>()
    val state = library.state

    init {
        viewModelScope.launch { library.load() }
    }
}

/** folder_picker_screen.dart: pick a destination folder; [onPicked] gets its path. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(onBack: () -> Unit, onPicked: (String) -> Unit, vm: FolderPickerViewModel = hiltViewModel()) {
    val c = AppTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val folders = state.folders.filter { it.path != vm.args.excludePath }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                // Flutter's AppBar: 56 dp tall (kToolbarHeight), 22 dp icons. Compose defaults are 64 / 24.
                expandedHeight = 56.dp,
                title = { Text(vm.args.title, fontSize = 16.sp, color = c.textPrimary) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(22.dp), tint = c.textSecondary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.surface),
            )
        },
        bottomBar = {
            Button(
                onClick = { selected?.let(onPicked) },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(48.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White),
            ) { Text(vm.args.confirmLabel) }
        },
    ) { padding ->
        if (folders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No other folders available", color = c.textMuted)
            }
        } else {
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 8.dp)) {
                items(folders, key = { it.path }) { f ->
                    FolderCard(
                        folder = f,
                        isExternal = false,
                        isNew = f.path in state.newPaths,
                        resumePosition = null,
                        onClick = { selected = f.path },
                        onResume = null,
                        // The Flutter picker gave no visible sign of the choice; show a check.
                        selected = f.path == selected,
                    )
                }
            }
        }
    }
}
