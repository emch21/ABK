package com.abk.kernel.extensions

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.abk.kernel.data.model.AbkRuntimeModule
import com.abk.kernel.data.model.AbkRuntimeStatus
import com.abk.kernel.utils.RootUtils
import com.google.gson.Gson
import org.json.JSONObject

const val ABK_EXTENSION_DISCOVERY_ACTION = "com.abk.kernel.action.ABK_EXTENSION"
const val ABK_EXTENSION_EXTRA_ID = "com.abk.kernel.extra.EXTENSION_ID"
const val ABK_EXTENSION_EXTRA_HOST_PACKAGE = "com.abk.kernel.extra.HOST_PACKAGE"
const val ABK_EXTENSION_EXTRA_HOST_PROVIDER = "com.abk.kernel.extra.HOST_PROVIDER"
const val ABK_EXTENSION_META_ID = "com.abk.kernel.extension.ID"
const val ABK_EXTENSION_META_NAME = "com.abk.kernel.extension.NAME"
const val ABK_EXTENSION_META_OOBE_ACTIVITY = "com.abk.kernel.extension.OOBE_ACTIVITY"
const val ABK_EXTENSION_META_SETTINGS_ACTIVITY = "com.abk.kernel.extension.SETTINGS_ACTIVITY"

data class AbkDiscoveredExtensionApp(
    val extensionId: String,
    val packageName: String,
    val displayName: String,
    val oobeComponent: ComponentName?,
    val settingsComponent: ComponentName?,
)

data class AbkExtensionState(
    val rawJson: String = "",
    val oobeCompleted: Boolean = false,
    val summary: String = "",
)

data class AbkManagedExtension(
    val moduleId: String,
    val extensionId: String,
    val name: String,
    val description: String,
    val companionPackage: String,
    val companionDisplayName: String,
    val companionAssetName: String,
    val companionDownloadUrl: String,
    val requiresCompanionApp: Boolean,
    val settingsSupported: Boolean,
    val perAppSupported: Boolean,
    val oobePriority: Int,
    val discoveredApp: AbkDiscoveredExtensionApp? = null,
    val state: AbkExtensionState? = null,
) {
    val isCompanionInstalled: Boolean
        get() = discoveredApp != null

    val needsOobe: Boolean
        get() = state?.oobeCompleted != true

    val summary: String
        get() = state?.summary.orEmpty()
}

fun abkExtensionHostAuthority(context: Context): String =
    "${context.packageName}.extensionhost"

fun abkLoadManagedExtensions(context: Context): List<AbkManagedExtension> {
    val runtimeStatus = RootUtils.readManagerRuntimeSnapshot().controlStatusJson
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Gson().fromJson(it, AbkRuntimeStatus::class.java) }.getOrNull() }
        ?: return emptyList()
    val discovered = discoverExtensionApps(context)

    return runtimeStatus.modules
        .filter { it.extensionId.isNotBlank() }
        .map { module -> toManagedExtension(module, discovered[module.extensionId]) }
        .sortedWith(
            compareByDescending<AbkManagedExtension> { it.needsOobe }
                .thenByDescending { it.oobePriority }
                .thenBy { it.name.lowercase() }
        )
}

fun abkPickPendingExtension(context: Context): AbkManagedExtension? =
    abkLoadManagedExtensions(context).firstOrNull { it.needsOobe }

fun abkOpenExtensionManager(
    context: Context,
    extensionId: String? = null,
    bootstrapMode: Boolean = false,
): Intent = Intent(context, AbkExtensionManagerActivity::class.java).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    extensionId?.takeIf { it.isNotBlank() }?.let {
        putExtra(ABK_EXTENSION_EXTRA_ID, it)
    }
    putExtra("bootstrap_mode", bootstrapMode)
}

fun abkLaunchExtensionOobe(activity: Activity, extension: AbkManagedExtension): Boolean {
    val component = extension.discoveredApp?.oobeComponent ?: return false
    activity.startActivity(
        Intent().setComponent(component)
            .putExtra(ABK_EXTENSION_EXTRA_ID, extension.extensionId)
            .putExtra(ABK_EXTENSION_EXTRA_HOST_PACKAGE, activity.packageName)
            .putExtra(ABK_EXTENSION_EXTRA_HOST_PROVIDER, abkExtensionHostAuthority(activity))
    )
    return true
}

fun abkLaunchExtensionSettings(activity: Activity, extension: AbkManagedExtension): Boolean {
    val component = extension.discoveredApp?.settingsComponent ?: return false
    activity.startActivity(
        Intent().setComponent(component)
            .putExtra(ABK_EXTENSION_EXTRA_ID, extension.extensionId)
            .putExtra(ABK_EXTENSION_EXTRA_HOST_PACKAGE, activity.packageName)
            .putExtra(ABK_EXTENSION_EXTRA_HOST_PROVIDER, abkExtensionHostAuthority(activity))
    )
    return true
}

private fun toManagedExtension(
    module: AbkRuntimeModule,
    discoveredApp: AbkDiscoveredExtensionApp?,
): AbkManagedExtension {
    val state = RootUtils.readAbkExtensionState(module.extensionId)?.let(::parseExtensionState)
    return AbkManagedExtension(
        moduleId = module.id,
        extensionId = module.extensionId,
        name = module.name.ifBlank { module.extensionId },
        description = module.description,
        companionPackage = module.companionPackage.ifBlank { discoveredApp?.packageName.orEmpty() },
        companionDisplayName = module.companionDisplayName.ifBlank { discoveredApp?.displayName.orEmpty() },
        companionAssetName = module.companionAssetName,
        companionDownloadUrl = module.companionDownloadUrl,
        requiresCompanionApp = module.requiresCompanionApp,
        settingsSupported = module.settingsSupported,
        perAppSupported = module.perAppSupported,
        oobePriority = module.oobePriority,
        discoveredApp = discoveredApp?.takeIf {
            module.companionPackage.isBlank() || module.companionPackage == it.packageName
        },
        state = state,
    )
}

private fun parseExtensionState(rawJson: String): AbkExtensionState {
    val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return AbkExtensionState(rawJson = rawJson)
    return AbkExtensionState(
        rawJson = rawJson,
        oobeCompleted = json.optBoolean("oobe_completed", false),
        summary = json.optString("summary").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("settings")?.optString("mode").orEmpty()
    )
}

private fun discoverExtensionApps(context: Context): Map<String, AbkDiscoveredExtensionApp> {
    val intent = Intent(ABK_EXTENSION_DISCOVERY_ACTION)
    val resolves = queryIntentActivities(context.packageManager, intent)
    return resolves.mapNotNull { resolve ->
        val activityInfo = resolve.activityInfo ?: return@mapNotNull null
        val meta = activityInfo.metaData ?: return@mapNotNull null
        val extensionId = meta.getString(ABK_EXTENSION_META_ID)?.trim().orEmpty()
        if (extensionId.isBlank()) return@mapNotNull null
        val packageName = activityInfo.packageName
        val displayName = meta.getString(ABK_EXTENSION_META_NAME)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: activityInfo.loadLabel(context.packageManager)?.toString().orEmpty()
        val oobeComponent = meta.getString(ABK_EXTENSION_META_OOBE_ACTIVITY)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { componentNameFromString(packageName, it) }
        val settingsComponent = meta.getString(ABK_EXTENSION_META_SETTINGS_ACTIVITY)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { componentNameFromString(packageName, it) }
        AbkDiscoveredExtensionApp(
            extensionId = extensionId,
            packageName = packageName,
            displayName = displayName,
            oobeComponent = oobeComponent,
            settingsComponent = settingsComponent,
        )
    }.associateBy { it.extensionId }
}

private fun componentNameFromString(packageName: String, className: String): ComponentName {
    val normalized = if (className.startsWith('.')) "$packageName$className" else className
    return ComponentName(packageName, normalized)
}

@Suppress("DEPRECATION")
private fun queryIntentActivities(
    packageManager: PackageManager,
    intent: Intent,
): List<ResolveInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
    } else {
        packageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA)
    }
}
