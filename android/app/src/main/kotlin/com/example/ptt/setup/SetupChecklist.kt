package com.example.ptt.setup

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.example.ptt.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The rows of the spec's "Permissions and setup checklist" table. [wireKey] is the stable snake_case key
 * under which a pass/fail is reported in `PUT /v1/devices/me` `checklist`; it is null for rows that
 * are never reported (informational rows and the OEM row, which cannot be checked). Never rename a key.
 */
enum class ChecklistItem(val wireKey: String?, @StringRes val title: Int, @StringRes val why: Int) {
    NOTIFICATIONS("notifications", R.string.check_notifications, R.string.check_notifications_why),
    LOCK_SCREEN("lock_screen", R.string.check_lock_screen, R.string.check_lock_screen_why),
    MICROPHONE("microphone", R.string.check_microphone, R.string.check_microphone_why),
    FULL_SCREEN("full_screen", R.string.check_full_screen, R.string.check_full_screen_why),
    DND_ACCESS("dnd_access", R.string.check_dnd, R.string.check_dnd_why),
    BATTERY("battery", R.string.check_battery, R.string.check_battery_why),
    OEM_BACKGROUND(null, R.string.check_oem, R.string.check_oem_why),
    INSTALL_PACKAGES("install_packages", R.string.check_install, R.string.check_install_why),
    FOREGROUND_SERVICES(null, R.string.check_fgs, R.string.check_fgs_why)
}

enum class RowStatus {
    /** Checked and fine (green). */
    PASS,

    /** Checked and not fine (red). */
    FAIL,

    /** Android cannot report this reliably: the user has to look (neither green nor red). */
    UNKNOWN,

    /** Informational, or nothing to do on this phone (neither green nor red). */
    INFO
}

data class ChecklistRow(val item: ChecklistItem, val status: RowStatus, val oem: List<OemSetting> = emptyList())

data class ChecklistState(val rows: List<ChecklistRow>) {
    /** True when any checked row is red. Rows that cannot be checked never raise the badge. */
    val hasFailures: Boolean get() = rows.any { it.status == RowStatus.FAIL }

    /**
     * What is reported to the server: snake_case key to pass/fail, booleans only, for rows that were
     * actually checked. Unverifiable and informational rows are left out (never reported as true).
     */
    fun serverMap(): Map<String, Boolean> = rows.mapNotNull { row ->
        val key = row.item.wireKey
        if (key != null && (row.status == RowStatus.PASS || row.status == RowStatus.FAIL)) {
            key to (row.status == RowStatus.PASS)
        } else {
            null
        }
    }.toMap()
}

/** What the device reports to the server; [DeviceRegistration] sends it only when it changed. */
fun interface ChecklistSource {
    fun serverMap(): Map<String, Boolean>
}

/**
 * Evaluates the checklist on demand ([refresh]) and exposes the latest result as [state] (the widget badge
 * of milestone 3 collects it). Call [refresh] on every app start and resume.
 */
class SetupChecklist(private val probe: SystemProbe) : ChecklistSource {
    private val _state = MutableStateFlow(evaluate())
    val state: StateFlow<ChecklistState> = _state

    /** The same as [state], typed as a plain flow for consumers that only collect. */
    val flow: Flow<ChecklistState> get() = _state

    fun evaluate(): ChecklistState {
        val oem = OemSettings.forManufacturer(probe.manufacturer)
        return ChecklistState(
            listOf(
                ChecklistRow(
                    ChecklistItem.NOTIFICATIONS,
                    both(probe.notificationPermission(), probe.notificationsEnabled())
                ),
                ChecklistRow(ChecklistItem.LOCK_SCREEN, status(probe.lockScreenNotifications())),
                ChecklistRow(ChecklistItem.MICROPHONE, status(probe.microphone())),
                ChecklistRow(ChecklistItem.FULL_SCREEN, status(probe.fullScreenIntent())),
                ChecklistRow(ChecklistItem.DND_ACCESS, status(probe.dndAccess())),
                ChecklistRow(ChecklistItem.BATTERY, status(probe.ignoringBatteryOptimizations())),
                ChecklistRow(
                    ChecklistItem.OEM_BACKGROUND,
                    if (oem.isEmpty()) RowStatus.INFO else RowStatus.UNKNOWN,
                    oem
                ),
                ChecklistRow(ChecklistItem.INSTALL_PACKAGES, status(probe.canInstallPackages())),
                // Declared in the manifest; the services that use them arrive with the alert and talk PRs.
                ChecklistRow(ChecklistItem.FOREGROUND_SERVICES, RowStatus.INFO)
            )
        )
    }

    fun refresh(): ChecklistState = evaluate().also { _state.value = it }

    override fun serverMap(): Map<String, Boolean> = refresh().serverMap()

    private fun status(value: Boolean?): RowStatus = when (value) {
        true -> RowStatus.PASS
        false -> RowStatus.FAIL
        null -> RowStatus.UNKNOWN
    }

    /** Red if either is off; green only if both are known to be on. */
    private fun both(a: Boolean?, b: Boolean?): RowStatus = when {
        a == false || b == false -> RowStatus.FAIL
        a == true && b == true -> RowStatus.PASS
        else -> RowStatus.UNKNOWN
    }
}

/** One manufacturer-specific instruction (the OEM row of the table). */
data class OemSetting(@StringRes val title: Int, @StringRes val instructions: Int)

/** Data-driven per-manufacturer instructions. Empty for Google, whose Pixels need nothing extra. */
object OemSettings {
    private val samsung = listOf(OemSetting(R.string.oem_samsung_title, R.string.oem_samsung_text))
    private val xiaomi = listOf(
        OemSetting(R.string.oem_xiaomi_autostart_title, R.string.oem_xiaomi_autostart_text),
        OemSetting(R.string.oem_xiaomi_lock_title, R.string.oem_xiaomi_lock_text)
    )
    private val huawei = listOf(OemSetting(R.string.oem_huawei_title, R.string.oem_huawei_text))
    private val oppo = listOf(OemSetting(R.string.oem_oppo_title, R.string.oem_oppo_text))
    private val vivo = listOf(OemSetting(R.string.oem_vivo_title, R.string.oem_vivo_text))

    /** Keys are lower-case `Build.MANUFACTURER` values. */
    private val byManufacturer: Map<String, List<OemSetting>> = mapOf(
        "samsung" to samsung,
        "xiaomi" to xiaomi,
        "redmi" to xiaomi,
        "poco" to xiaomi,
        "huawei" to huawei,
        "honor" to huawei,
        "oppo" to oppo,
        "oneplus" to oppo,
        "realme" to oppo,
        "vivo" to vivo
    )

    fun forManufacturer(manufacturer: String): List<OemSetting> = byManufacturer[manufacturer.lowercase()].orEmpty()
}

/** The Settings page for each row (the deep link). Callers fall back to [appDetails] if it cannot be opened. */
object ChecklistLinks {
    // This is a sideloaded family app, not a Play listing: the Play policy behind BatteryLife does not apply.
    @SuppressLint("BatteryLife")
    fun intentFor(item: ChecklistItem, packageName: String): Intent = when (item) {
        ChecklistItem.NOTIFICATIONS, ChecklistItem.LOCK_SCREEN ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

        ChecklistItem.MICROPHONE, ChecklistItem.OEM_BACKGROUND, ChecklistItem.FOREGROUND_SERVICES ->
            appDetails(packageName)

        ChecklistItem.FULL_SCREEN ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:$packageName".toUri())
            } else {
                appDetails(packageName)
            }

        ChecklistItem.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

        ChecklistItem.BATTERY ->
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())

        ChecklistItem.INSTALL_PACKAGES ->
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
    }

    fun appDetails(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
}
