package com.ayesha.player.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Rows up to ~5 cards beyond the screen in the scroll direction are composed ahead, in the idle
 * time between frames, and rows just scrolled past stay composed. The default (one row ahead)
 * left most new rows to be built inside a scroll frame, which on a budget phone took longer
 * than the frame itself. Defined once: the list state is keyed on it.
 */
@OptIn(ExperimentalFoundationApi::class)
private val ListCacheWindow = LazyLayoutCacheWindow(ahead = 480.dp, behind = 240.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPrefetchingListState(): LazyListState = rememberLazyListState(cacheWindow = ListCacheWindow)
