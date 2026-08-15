package com.oldchat.material.feature.discover

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.oldchat.material.OldChatApplication

/**
 * 表情广场（发现页）。
 * 样式：搜索框+按钮 / 筛选（广场/我上传的）/ 第x页(每页50)·共x个 / 表情列表（图+标题+保存+自己的删除按钮）。
 * 右下角上传按钮 → 对话框输入标题 + 选文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPlazaScreen(
    onBack: () -> Unit = {},
    viewModel: EmojiPlazaViewModel = viewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()
    val savedIds by viewModel.savedIds.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var searchText by remember { mutableStateOf("") }
    var showUploadDialog by remember { mutableStateOf(false) }

    val app = OldChatApplication.instance

    // 上传选文件
    var pendingUploadUri by remember { mutableStateOf<Uri?>(null) }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { pendingUploadUri = it }
    }

    // 上传对话框内部状态
    var uploadTitle by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("表情广场") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showUploadDialog = true }) {
                Icon(Icons.Filled.Add, "上传表情")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 搜索框 + 按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("搜索表情…") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { viewModel.search(searchText.trim()) }) {
                    Icon(Icons.Filled.Search, "搜索")
                }
            }

            // 筛选项
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { viewModel.switchTab(0) },
                    label = { Text("广场") }
                )
                FilterChip(
                    selected = tab == 1,
                    onClick = { viewModel.switchTab(1) },
                    label = { Text("我上传的") }
                )
            }

            // 分页信息：第x页（每页50个）·共x个
            Text(
                "第 $page 页（每页 50 个）· 共 $total 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (keyword.isNotEmpty()) {
                Text(
                    "搜索「$keyword」结果",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 表情列表
            if (isLoading && items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        EmojiRow(
                            item = item,
                            saved = item.id in savedIds,
                            isOwn = viewModel.isOwn(item),
                            onSave = { viewModel.saveToMine(item) },
                            onDelete = { viewModel.delete(item) }
                        )
                    }
                }
            }

            // 分页按钮
            if (tab == 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = { viewModel.prevPage() },
                        enabled = page > 1
                    ) {
                        Icon(Icons.Filled.ChevronLeft, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上一页")
                    }
                    OutlinedButton(
                        onClick = { viewModel.nextPage() },
                        enabled = items.size == 50
                    ) {
                        Text("下一页")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    // 上传对话框
    if (showUploadDialog) {
        AlertDialog(
            onDismissRequest = { showUploadDialog = false; uploadTitle = "" },
            title = { Text("上传表情") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uploadTitle,
                        onValueChange = { uploadTitle = it },
                        label = { Text("标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { filePicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Image, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (pendingUploadUri != null) "已选择文件" else "选择文件")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = uploadTitle.isNotBlank() && pendingUploadUri != null,
                    onClick = {
                        pendingUploadUri?.let { uri ->
                            viewModel.upload(uploadTitle.trim(), uri, context) { ok, err ->
                                if (ok) {
                                    showUploadDialog = false
                                    uploadTitle = ""
                                    pendingUploadUri = null
                                }
                            }
                        }
                    }
                ) { Text("上传") }
            },
            dismissButton = {
                TextButton(onClick = { showUploadDialog = false; uploadTitle = ""; pendingUploadUri = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 提示信息（预留：后续可接入 Snackbar 展示 message）
}

/**
 * 单个表情行：图片 + 标题 + 保存按钮（自己的则额外红色删除按钮）。
 */
@Composable
private fun EmojiRow(
    item: EmojiPlazaViewModel.EmojiItem,
    saved: Boolean,
    isOwn: Boolean,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    val app = OldChatApplication.instance
    val resolvedUrl = remember(item.mediaUrl) { app.serverConfig.resolveMediaUrl(item.mediaUrl) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 表情图片
            if (resolvedUrl != null) {
                AsyncImage(
                    model = resolvedUrl,
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.EmojiEmotions, null, modifier = Modifier.size(32.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            // 标题 + 上传者
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name.ifEmpty { "未命名" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    item.ownerName.ifEmpty { "未知" } + (if (item.isGif) " · GIF" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Spacer(Modifier.width(8.dp))

            // 保存按钮
            if (!saved) {
                FilledTonalButton(onClick = onSave) {
                    Icon(Icons.Filled.BookmarkAdd, null, modifier = Modifier.size(18.dp))
                }
            } else {
                Icon(
                    Icons.Filled.Bookmark,
                    contentDescription = "已保存",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 自己的 → 红色删除按钮
            if (isOwn) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}