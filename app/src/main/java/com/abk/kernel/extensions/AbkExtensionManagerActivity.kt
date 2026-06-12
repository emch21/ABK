package com.abk.kernel.extensions

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.abk.kernel.R
import com.abk.kernel.ui.theme.AbkTheme
import com.abk.kernel.utils.DownloadUtils
import com.abk.kernel.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AbkExtensionManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val focusExtensionId = intent.getStringExtra(ABK_EXTENSION_EXTRA_ID)
        val bootstrapMode = intent.getBooleanExtra("bootstrap_mode", false)

        setContent {
            AbkTheme {
                AbkExtensionManagerScreen(
                    focusExtensionId = focusExtensionId,
                    bootstrapMode = bootstrapMode,
                    onBack = ::finish,
                    onRefresh = ::recreate,
                    onInstall = { extension ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val url = extension.companionDownloadUrl.trim()
                                if (url.isBlank()) {
                                    RootUtils.ShellResult(false, listOf(getString(R.string.extension_download_missing)))
                                } else {
                                    val download = DownloadUtils.downloadDirectAsset(
                                        context = this@AbkExtensionManagerActivity,
                                        token = null,
                                        url = url,
                                        name = extension.companionAssetName.ifBlank { "${extension.extensionId}.apk" },
                                        sizeBytes = 1L,
                                        runId = -1L,
                                        runTitle = extension.name,
                                    )
                                    val apkFile = download.artifacts.firstOrNull()?.filePath
                                    if (apkFile.isNullOrBlank()) {
                                        RootUtils.ShellResult(false, listOf(download.errorMessage ?: getString(R.string.extension_install_failed)))
                                    } else {
                                        RootUtils.installApk(this@AbkExtensionManagerActivity, apkFile)
                                    }
                                }
                            }
                            Toast.makeText(
                                this@AbkExtensionManagerActivity,
                                if (result.success) {
                                    getString(R.string.extension_install_success, extension.companionDisplayName.ifBlank { extension.name })
                                } else {
                                    result.output.lastOrNull() ?: getString(R.string.extension_install_failed)
                                },
                                Toast.LENGTH_LONG
                            ).show()
                            if (result.success) {
                                if (abkLaunchExtensionOobe(this@AbkExtensionManagerActivity, extension.copy(discoveredApp = extension.discoveredApp))) {
                                    finish()
                                } else {
                                    recreate()
                                }
                            }
                        }
                    },
                    onOpenOobe = { extension ->
                        if (!abkLaunchExtensionOobe(this, extension)) {
                            Toast.makeText(this, getString(R.string.extension_oobe_missing), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenSettings = { extension ->
                        if (!abkLaunchExtensionSettings(this, extension)) {
                            Toast.makeText(this, getString(R.string.extension_settings_missing), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onReset = { extension ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                RootUtils.clearAbkExtensionState(extension.extensionId)
                            }
                            recreate()
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AbkExtensionManagerScreen(
    focusExtensionId: String?,
    bootstrapMode: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstall: (AbkManagedExtension) -> Unit,
    onOpenOobe: (AbkManagedExtension) -> Unit,
    onOpenSettings: (AbkManagedExtension) -> Unit,
    onReset: (AbkManagedExtension) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var extensions by remember { mutableStateOf<List<AbkManagedExtension>>(emptyList()) }

    LaunchedEffect(Unit) {
        extensions = withContext(Dispatchers.IO) { abkLoadManagedExtensions(context) }
        loading = false
    }

    val focused = remember(extensions, focusExtensionId) {
        extensions.firstOrNull { it.extensionId == focusExtensionId }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(if (bootstrapMode) "ABK 扩展初始化" else "ABK 扩展") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(Modifier.padding(start = 24.dp))
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            focused?.let { extension ->
                item("focus-${extension.extensionId}") {
                    ExtensionCard(
                        extension = extension,
                        emphasize = true,
                        onInstall = onInstall,
                        onOpenOobe = onOpenOobe,
                        onOpenSettings = onOpenSettings,
                        onReset = onReset
                    )
                }
            }
            items(
                items = extensions.filter { it.extensionId != focused?.extensionId },
                key = { it.extensionId }
            ) { extension ->
                ExtensionCard(
                    extension = extension,
                    emphasize = false,
                    onInstall = onInstall,
                    onOpenOobe = onOpenOobe,
                    onOpenSettings = onOpenSettings,
                    onReset = onReset
                )
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: AbkManagedExtension,
    emphasize: Boolean,
    onInstall: (AbkManagedExtension) -> Unit,
    onOpenOobe: (AbkManagedExtension) -> Unit,
    onOpenSettings: (AbkManagedExtension) -> Unit,
    onReset: (AbkManagedExtension) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (emphasize) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(extension.name, style = MaterialTheme.typography.titleMedium)
            if (extension.description.isNotBlank()) {
                Text(extension.description, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                if (extension.needsOobe) "需要首次配置" else "已完成首次配置",
                style = MaterialTheme.typography.bodySmall
            )
            if (extension.summary.isNotBlank()) {
                Text(extension.summary, style = MaterialTheme.typography.bodySmall)
            }
            if (!extension.isCompanionInstalled) {
                Text(
                    text = "未安装扩展应用：${extension.companionDisplayName.ifBlank { extension.companionPackage.ifBlank { "Unknown" } }}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!extension.isCompanionInstalled) {
                    Button(
                        onClick = { onInstall(extension) },
                        enabled = extension.companionDownloadUrl.isNotBlank()
                    ) {
                        Icon(Icons.Default.InstallMobile, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        Text("安装扩展")
                    }
                } else {
                    Button(onClick = { onOpenOobe(extension) }) {
                        Icon(Icons.Default.Build, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        Text("运行 OOBE")
                    }
                    if (extension.settingsSupported) {
                        Button(onClick = { onOpenSettings(extension) }) {
                            Icon(Icons.Default.Extension, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 2.dp))
                            Text("扩展设置")
                        }
                    }
                }
                Button(onClick = { onReset(extension) }) {
                    Text("重置")
                }
            }
        }
    }
}
