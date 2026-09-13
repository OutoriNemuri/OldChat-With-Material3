package com.oldchat.material.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oldchat.material.OldChatApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * CIP Developer IDE — file tree + editor + preview + test/pack/export.
 *
 * Mirrors CipDeveloperActivity from §10.5:
 * - File tree + editor + preview
 * - Test/pack/export ZIP
 * - configChanges=keyboardHidden|orientation|screenSize to avoid rotation rebuild
 * - Disable global press feedback: useSquarePressFeedback() returns false
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CipDeveloperScreen(
    onBack: () -> Unit = {},
    embedded: Boolean = false,
    devViewModel: CipDevViewModel = viewModel()
) {
    var selectedFile: String? by remember { mutableStateOf<String?>(null) }
    val fileTree by devViewModel.fileTree.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar（内嵌时不显示，由 CipCenterScreen 提供）
        if (!embedded) {
            TopAppBar(
                title = { Text("CIP 开发") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // Test button
                    IconButton(onClick = { devViewModel.testPackage() }) {
                        Icon(Icons.Filled.PlayArrow, "测试")
                    }
                    // Export ZIP button
                    IconButton(onClick = { devViewModel.exportZip() }) {
                        Icon(Icons.Filled.Archive, "导出")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        Row(modifier = Modifier.weight(1f)) {
            // File tree sidebar
            Surface(
                modifier = Modifier
                    .width(120.dp)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(fileTree, key = { it }) { file ->
                        Text(
                            file,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFile = file }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (file == selectedFile) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (file == selectedFile)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            VerticalDivider()

            // Editor area
            if (selectedFile != null) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // File name header
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                selectedFile ?: "",
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { devViewModel.deleteFile(selectedFile!!) }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    // Code editor (basic)
                    var code by remember(selectedFile) { mutableStateOf(devViewModel.readFile(selectedFile ?: "")) }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { newCode ->
                            code = newCode
                            devViewModel.writeFile(selectedFile!!, newCode)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                        )
                    )
                }
            } else {
                // Empty editor placeholder
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("选择文件开始编辑",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { devViewModel.createNewFile() }) {
                            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("新建文件")
                        }
                    }
                }
            }
        }
    }
}

class CipDevViewModel : ViewModel() {

    private val _fileTree = MutableStateFlow(listOf("manifest.json", "main.lua", "assets/icon.png"))
    val fileTree: StateFlow<List<String>> = _fileTree.asStateFlow()

    private val fileContents = mutableMapOf(
        "manifest.json" to """{
  "id": "my-app",
  "name": "My App",
  "description": "A CIP mini-app",
  "version": "1.0.0",
  "icon_url": "",
  "permissions": ["network"],
  "allowed_hosts": [],
  "enabled": true
}""",
        "main.lua" to """-- CIP App Entry Point
function on_create()
    local label = ui.label("Hello, OldChat!")
    label:set_text_size(18)
    label:set_color("#000000")
    return label
end

function on_resume()
    -- Called when app is resumed
end

function on_destroy()
    -- Cleanup resources
end""",
        "assets/icon.png" to "[binary placeholder]"
    )

    fun readFile(path: String): String {
        return fileContents[path] ?: ""
    }

    fun writeFile(path: String, content: String) {
        fileContents[path] = content
    }

    fun createNewFile() {
        viewModelScope.launch {
            val fileName = "new_file_${System.currentTimeMillis()}.lua"
            fileContents[fileName] = "-- New file"
            _fileTree.value = _fileTree.value + fileName
        }
    }

    fun deleteFile(path: String) {
        if (path == "manifest.json" || path == "main.lua") return // Can't delete mandatory files
        fileContents.remove(path)
        _fileTree.value = _fileTree.value.filter { it != path }
    }

    fun testPackage() {
        // TODO: Validate manifest, compile Lua, run in sandbox
    }

    fun exportZip() {
        // TODO: Pack fileContents into ZIP, share via ACTION_SEND
    }
}
