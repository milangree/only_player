package one.only.player.feature.videopicker.screens.cloud

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.only.player.core.model.RemoteFile
import one.only.player.core.ui.R
import one.only.player.core.ui.components.NextDialog
import one.only.player.core.ui.components.NextSegmentedListItem
import one.only.player.core.ui.components.NextTopAppBar
import one.only.player.core.ui.designsystem.NextIcons
import one.only.player.core.ui.extensions.copy
import one.only.player.core.ui.extensions.withBottomFallback
import one.only.player.feature.videopicker.composables.VideoInfoDialog

@Composable
fun CloudBrowseRoute(
    viewModel: CloudBrowseViewModel = hiltViewModel(),
    onNavigateUp: () -> Unit,
    onDirectoryClick: (serverId: Long, path: String) -> Unit,
    onPlayVideo: (uri: Uri, headers: Map<String, String>, initialSubtitleDirectoryUri: Uri?, playlist: List<Uri>, playlistRemotePaths: List<String>) -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 从播放器返回时刷新播放状态
    LifecycleResumeEffect(Unit) {
        viewModel.onEvent(CloudBrowseEvent.RefreshPlaybackStates)
        onPauseOrDispose {}
    }

    CloudBrowseScreen(
        uiState = uiState,
        onNavigateUp = onNavigateUp,
        onEvent = viewModel::onEvent,
        onDirectoryClick = { path ->
            val serverId = uiState.server?.id ?: return@CloudBrowseScreen
            onDirectoryClick(serverId, path)
        },
        onFileClick = { file ->
            val url = viewModel.buildPlayUrl(file) ?: return@CloudBrowseScreen
            val headers = viewModel.buildAuthHeaders(file)
            val initialSubtitleDirectoryUri = viewModel.buildCurrentDirectoryDocumentId()
                ?.let { documentId ->
                    DocumentsContract.buildDocumentUri("${context.packageName}.documents", documentId)
                }
            val playlist = viewModel.buildAllVideoPlayUrls()
            val playlistRemotePaths = viewModel.buildAllVideoRemotePaths()
            onPlayVideo(Uri.parse(url), headers, initialSubtitleDirectoryUri, playlist, playlistRemotePaths)
        },
        onFileInfoClick = { file ->
            val documentUri = viewModel.buildFileDocumentId(file)
                ?.let { documentId ->
                    DocumentsContract.buildDocumentUri("${context.packageName}.documents", documentId)
                } ?: return@CloudBrowseScreen
            viewModel.onEvent(CloudBrowseEvent.LoadFileInfo(file, documentUri))
        },
        onFavoriteCurrentDirectory = {
            viewModel.onEvent(CloudBrowseEvent.AddCurrentDirectoryFavorite)
        },
        onFavoriteFile = { file ->
            viewModel.onEvent(CloudBrowseEvent.AddFavorite(file))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CloudBrowseScreen(
    uiState: CloudBrowseUiState,
    onNavigateUp: () -> Unit = {},
    onEvent: (CloudBrowseEvent) -> Unit = {},
    onDirectoryClick: (String) -> Unit = {},
    onFileClick: (RemoteFile) -> Unit = {},
    onFileInfoClick: (RemoteFile) -> Unit = {},
    onFavoriteCurrentDirectory: () -> Unit = {},
    onFavoriteFile: (RemoteFile) -> Unit = {},
) {
    val serverName = uiState.server?.name?.takeIf { it.isNotBlank() }
        ?: uiState.server?.host
        ?: stringResource(R.string.browsing)
    val haptic = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    var selectedFilePaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var shouldShowSelectionMenu by remember { mutableStateOf(false) }
    var restoredDirectoryPath by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedItemsSize = selectedFilePaths.size
    val totalItemsSize = uiState.files.count { !it.isDirectory }
    val isInSelectionMode = selectedFilePaths.isNotEmpty()

    fun clearSelection() {
        selectedFilePaths = emptySet()
        shouldShowSelectionMenu = false
    }

    fun toggleFileSelection(file: RemoteFile) {
        if (file.isDirectory) return
        selectedFilePaths = if (file.path in selectedFilePaths) {
            selectedFilePaths - file.path
        } else {
            selectedFilePaths + file.path
        }
        if (selectedFilePaths.isEmpty()) {
            shouldShowSelectionMenu = false
        }
    }

    LaunchedEffect(uiState.currentPath) {
        restoredDirectoryPath = null
        clearSelection()
    }

    LaunchedEffect(uiState.files) {
        selectedFilePaths = selectedFilePaths
            .filter { path -> uiState.files.any { file -> !file.isDirectory && file.path == path } }
            .toSet()
    }

    LaunchedEffect(
        uiState.currentPath,
        uiState.restoreTargetFilePath,
        uiState.files,
    ) {
        if (!uiState.preferences.shouldRestoreLastPlayedMediaInFolders) return@LaunchedEffect
        if (restoredDirectoryPath == uiState.currentPath) return@LaunchedEffect
        val restoreTargetFilePath = uiState.restoreTargetFilePath ?: return@LaunchedEffect
        val targetIndex = uiState.files.indexOfFirst { file -> file.path == restoreTargetFilePath }
        if (targetIndex < 0) return@LaunchedEffect
        lazyListState.scrollToItem(targetIndex)
        restoredDirectoryPath = uiState.currentPath
    }

    // 出错时直接允许返回上级页面，不再反复重试 PROPFIND
    BackHandler(enabled = isInSelectionMode) {
        clearSelection()
    }

    BackHandler(enabled = !isInSelectionMode && !uiState.isAtRoot && !uiState.isError) {
        onNavigateUp()
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = serverName.takeIf { !isInSelectionMode } ?: "",
                navigationIcon = {
                    if (isInSelectionMode) {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { clearSelection() }
                                .padding(8.dp)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = NextIcons.Close,
                                contentDescription = stringResource(id = R.string.navigate_up),
                            )
                            Text(
                                text = stringResource(R.string.m_n_selected, selectedItemsSize, totalItemsSize),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    } else {
                        FilledTonalIconButton(onClick = {
                            onNavigateUp()
                        }) {
                            Icon(
                                imageVector = NextIcons.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_up),
                            )
                        }
                    }
                },
                actions = {
                    if (isInSelectionMode) {
                        FilledTonalIconButton(
                            onClick = {
                                selectedFilePaths = if (selectedItemsSize != totalItemsSize) {
                                    uiState.files
                                        .filter { !it.isDirectory }
                                        .map { it.path }
                                        .toSet()
                                } else {
                                    emptySet()
                                }
                                if (selectedFilePaths.isEmpty()) {
                                    shouldShowSelectionMenu = false
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (selectedItemsSize != totalItemsSize) {
                                    NextIcons.SelectAll
                                } else {
                                    NextIcons.DeselectAll
                                },
                                contentDescription = if (selectedItemsSize != totalItemsSize) {
                                    stringResource(R.string.select_all)
                                } else {
                                    stringResource(R.string.deselect_all)
                                },
                            )
                        }
                        Box {
                            FilledTonalIconButton(
                                onClick = { shouldShowSelectionMenu = true },
                                enabled = selectedItemsSize == 1,
                                modifier = Modifier.testTag("btn_cloud_selection_actions"),
                            ) {
                                Icon(
                                    imageVector = NextIcons.Menu,
                                    contentDescription = stringResource(id = R.string.menu),
                                )
                            }
                            CloudSelectionActionsMenu(
                                expanded = shouldShowSelectionMenu,
                                onDismissRequest = { shouldShowSelectionMenu = false },
                                onFavoriteAction = {
                                    shouldShowSelectionMenu = false
                                    val selectedPath = selectedFilePaths.firstOrNull() ?: return@CloudSelectionActionsMenu
                                    val file = uiState.files.firstOrNull { it.path == selectedPath } ?: return@CloudSelectionActionsMenu
                                    onFavoriteFile(file)
                                    clearSelection()
                                },
                                onInfoAction = {
                                    shouldShowSelectionMenu = false
                                    val selectedPath = selectedFilePaths.firstOrNull() ?: return@CloudSelectionActionsMenu
                                    val file = uiState.files.firstOrNull { it.path == selectedPath } ?: return@CloudSelectionActionsMenu
                                    onFileInfoClick(file)
                                    clearSelection()
                                },
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onFavoriteCurrentDirectory,
                            modifier = Modifier.testTag("btn_cloud_favorite_current_directory"),
                        ) {
                            Icon(
                                imageVector = NextIcons.LibraryBooks,
                                contentDescription = stringResource(R.string.add_to_favorites),
                            )
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(start = innerPadding.calculateStartPadding(LocalLayoutDirection.current)),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                val contentPadding = innerPadding.copy(top = 0.dp, start = 0.dp).withBottomFallback()
                PullToRefreshBox(
                    modifier = Modifier.fillMaxSize(),
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { onEvent(CloudBrowseEvent.Retry) },
                ) {
                    when {
                        uiState.isLoading && uiState.files.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        uiState.isError -> {
                            CloudBrowseMessageState(
                                contentPadding = contentPadding,
                                icon = NextIcons.Cloud,
                                title = stringResource(R.string.connection_failed),
                                message = uiState.errorMessage,
                                actionText = stringResource(R.string.retry),
                                onActionClick = { onEvent(CloudBrowseEvent.Retry) },
                            )
                        }

                        uiState.files.isEmpty() -> {
                            CloudBrowseMessageState(
                                contentPadding = contentPadding,
                                icon = NextIcons.Folder,
                                title = stringResource(R.string.empty_directory),
                            )
                        }

                        else -> {
                            val mostRecentFilePath = uiState.playbackStates.entries
                                .maxByOrNull { it.value.lastPlayedTime ?: 0L }
                                ?.takeIf { (it.value.lastPlayedTime ?: 0L) > 0L }
                                ?.key

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                state = lazyListState,
                                contentPadding = contentPadding,
                                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                            ) {
                                itemsIndexed(
                                    uiState.files,
                                    key = { _, file -> file.path },
                                ) { index, file ->
                                    val playbackInfo = uiState.playbackStates[file.path]
                                    val isRecentlyPlayed = !file.isDirectory && file.path == mostRecentFilePath
                                    val hasBeenPlayed = playbackInfo != null && playbackInfo.playbackPosition > 0
                                    val isSelected = file.path in selectedFilePaths
                                    RemoteFileItem(
                                        file = file,
                                        isFirstItem = index == 0,
                                        isLastItem = index == uiState.files.lastIndex,
                                        isRecentlyPlayed = isRecentlyPlayed,
                                        hasBeenPlayed = hasBeenPlayed,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (isInSelectionMode) {
                                                if (!file.isDirectory) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                                    toggleFileSelection(file)
                                                }
                                                return@RemoteFileItem
                                            }
                                            if (file.isDirectory) {
                                                onDirectoryClick(file.path)
                                            } else {
                                                onFileClick(file)
                                            }
                                        },
                                        onLongClick = if (file.isDirectory) {
                                            null
                                        } else {
                                            {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                toggleFileSelection(file)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (uiState.isLoadingFileInfo) {
        RemoteFileInfoLoadingDialog(
            onDismiss = { onEvent(CloudBrowseEvent.DismissFileInfo) },
        )
    }

    uiState.infoVideo?.let { video ->
        VideoInfoDialog(
            video = video,
            onDismiss = { onEvent(CloudBrowseEvent.DismissFileInfo) },
        )
    }
}

@Composable
private fun CloudBrowseMessageState(
    contentPadding: PaddingValues,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.padding(top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (!actionText.isNullOrBlank() && onActionClick != null) {
                TextButton(onClick = onActionClick) {
                    Text(text = actionText)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RemoteFileItem(
    file: RemoteFile,
    isFirstItem: Boolean,
    isLastItem: Boolean,
    isRecentlyPlayed: Boolean = false,
    hasBeenPlayed: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val highlightColor = MaterialTheme.colorScheme.primary
    NextSegmentedListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        isSelected = isSelected,
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        modifier = Modifier.testTag("remote_file_${file.name}"),
        leadingContent = {
            Icon(
                imageVector = if (file.isDirectory) NextIcons.Folder else NextIcons.Video,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isRecentlyPlayed) highlightColor else LocalContentColor.current,
            )
        },
        supportingContent = if (!file.isDirectory) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (file.size > 0) {
                        Text(
                            text = formatFileSize(file.size),
                            color = if (isRecentlyPlayed) highlightColor else Color.Unspecified,
                        )
                    }
                    if (hasBeenPlayed) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(highlightColor, CircleShape),
                        )
                    }
                }
            }
        } else {
            null
        },
        content = {
            Text(
                text = file.name,
                color = if (isRecentlyPlayed) highlightColor else Color.Unspecified,
            )
        },
    )
}

@Composable
private fun CloudSelectionActionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onFavoriteAction: () -> Unit,
    onInfoAction: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("menu_cloud_selection_actions"),
        shape = RoundedCornerShape(10.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
        ),
    ) {
        CloudSelectionMenuItem(
            text = stringResource(id = R.string.add_to_favorites),
            icon = NextIcons.LibraryBooks,
            testTag = "item_cloud_selection_add_favorites",
            onClick = onFavoriteAction,
        )
        CloudSelectionMenuItem(
            text = stringResource(id = R.string.info),
            icon = NextIcons.Info,
            testTag = "item_cloud_selection_info",
            onClick = onInfoAction,
        )
    }
}

@Composable
private fun CloudSelectionMenuItem(
    text: String,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text = text) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        },
        modifier = Modifier.testTag(testTag),
        onClick = onClick,
    )
}

@Composable
private fun RemoteFileInfoLoadingDialog(
    onDismiss: () -> Unit,
) {
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.info)) },
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {},
        modifier = Modifier.testTag("remote_file_info_loading_dialog"),
    )
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
