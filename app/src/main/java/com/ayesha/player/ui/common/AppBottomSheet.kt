package com.ayesha.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.ayesha.player.core.theme.AppRadius
import com.ayesha.player.core.theme.AppTheme
import kotlinx.coroutines.launch

/** Lets sheet content close its sheet with the slide-down animation before acting. */
fun interface SheetCloser {
    /** Animates the sheet away, then runs [then]. */
    fun close(then: () -> Unit)
}

/**
 * sheet_surface.dart: every bottom sheet's shell. Panel color, 20 dp top corners, drag handle,
 * at most 90 % of the screen height with the body scrolling, so nothing is clipped off-screen
 * in landscape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    maxHeightFraction: Float = 0.9f,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.(SheetCloser) -> Unit,
) {
    val c = AppTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val closer = SheetCloser { then ->
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * maxHeightFraction

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.panel,
        shape = AppRadius.sheet,
        dragHandle = {
            Box(Modifier.padding(vertical = 12.dp).size(36.dp, 4.dp).background(c.border, RoundedCornerShape(2.dp)))
        },
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            horizontalAlignment = Alignment.Start,
        ) {
            content(closer)
            Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
        }
    }
}
