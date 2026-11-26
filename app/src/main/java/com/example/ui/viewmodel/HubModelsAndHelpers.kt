package com.example.ui.viewmodel

import com.example.data.model.FamilyExpense
import com.example.data.model.GameSession
import com.example.data.model.LocationRecord
import com.example.data.model.NetworkEventModel
import com.example.data.model.NetworkSessionModel
import com.example.data.model.PairedDevice
import com.example.data.model.FamilyMember
import com.example.service.LocationCaptureHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class DeviceActivityKind {
    NETWORK,
    SESSION,
    LOCATION,
    BATTERY_STATUS,
    CONSENT_PERM,
    BOOT
}

data class UnifiedDeviceActivityItem(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val kind: DeviceActivityKind,
    val title: String,
    val subtitle: String,
    val timestampMs: Long
)

enum class GlobalSearchKind {
    MESSAGE,
    DEVICE,
    MEMBER,
    TASK,
    CALENDAR,
    NOTICE,
    VAULT
}

data class GlobalSearchResult(
    val id: String,
    val kind: GlobalSearchKind,
    val title: String,
    val subtitle: String,
    val timestampMs: Long,
    val targetRoute: String,
    val extraId: String? = null
)

data class TicTacToeOnlineStats(
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val active: Int
)

fun computeTicTacToeOnlineStats(games: List<GameSession>, currentUserId: String): TicTacToeOnlineStats {
    var wins = 0
    var losses = 0
    var draws = 0
    var active = 0
    for (g in games) {
        when (g.status) {
            "ACTIVE" -> active++
            "DRAW" -> draws++
            "X_WON" -> if (g.playerXUid == currentUserId) wins++ else losses++
            "O_WON" -> if (g.playerOUid == currentUserId) wins++ else losses++
        }
    }
    return TicTacToeOnlineStats(wins = wins, losses = losses, draws = draws, active = active)
}

fun formatDurationShort(ms: Long): String {
    val safeSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = safeSec / 3600L
    val mins = (safeSec % 3600L) / 60L
    val secs = safeSec % 60L
    return when {
        hrs > 0 -> "${hrs}h ${mins}m"
        mins > 0 -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}

/**
 * Computes member presence text strictly respecting LastSeenPrivacy (EVERYONE / ADMIN_ONLY / NOBODY),
 * availability status, online window (<= 5 min), and live typing indicator (<= 8s).
 */
fun formatLastSeenForViewer(
    member: FamilyMember,
    viewerIsAdmin: Boolean,
    conversationId: String? = null,
    nowMs: Long = System.currentTimeMillis()
): String {
    if (conversationId != null &&
        member.typingConversationId == conversationId &&
        member.typingActivity != "NONE" &&
        (nowMs - member.typingUpdatedAtMs) in 0..8_000L
    ) {
        return when (member.typingActivity) {
            "RECORDING" -> "Recording voice note..."
            "SENDING" -> "Sending attachment..."
            else -> "Typing..."
        }
    }

    val isOnlineNow = member.online && (nowMs - member.lastSeenAtMs) in 0..(5 * 60 * 1000L)
    val availSuffix = when (member.availability) {
        "BUSY" -> " • Busy"
        "AWAY" -> " • Away"
        else -> ""
    }
    if (isOnlineNow) {
        return "Online$availSuffix"
    }

    val canSeeLastSeen = when (member.lastSeenPrivacy) {
        "NOBODY" -> false
        "ADMIN_ONLY" -> viewerIsAdmin
        else -> true
    }
    if (!canSeeLastSeen) {
        return when (member.availability) {
            "BUSY" -> "Busy"
            "AWAY" -> "Away"
            else -> "Last seen hidden"
        }
    }
    return if (member.lastSeenAtMs <= 0L) {
        "Offline$availSuffix"
    } else {
        "Last seen ${LocationCaptureHelper.formatRelativeTime(member.lastSeenAtMs, nowMs)}$availSuffix"
    }
}

/**
 * Builds an Admin-only unified chronological activity timeline for paired devices.
 * Intentionally excludes private chat text or message bodies.
 */
fun buildUnifiedDeviceTimeline(
    devices: List<PairedDevice>,
    networkEvents: List<NetworkEventModel>,
    networkSessions: List<NetworkSessionModel>,
    locations: List<LocationRecord>,
    selectedDeviceId: String? = null
): List<UnifiedDeviceActivityItem> {
    val items = mutableListOf<UnifiedDeviceActivityItem>()
    val targetDevices = if (selectedDeviceId.isNullOrBlank()) devices else devices.filter { it.id == selectedDeviceId }
    val devNameById = devices.associate { it.id to it.deviceName }

    for (evt in networkEvents) {
        if (!selectedDeviceId.isNullOrBlank() && evt.deviceId != selectedDeviceId) continue
        val kind = when (evt.eventType) {
            "DEVICE_BOOTED" -> DeviceActivityKind.BOOT
            "MONITORING_STARTED", "MONITORING_PAUSED" -> DeviceActivityKind.CONSENT_PERM
            else -> DeviceActivityKind.NETWORK
        }
        items += UnifiedDeviceActivityItem(
            id = "ne_${evt.id}",
            deviceId = evt.deviceId,
            deviceName = evt.deviceName.ifBlank { devNameById[evt.deviceId] ?: evt.deviceId },
            kind = kind,
            title = evt.eventType.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
            subtitle = evt.details ?: "${evt.networkType} • ${evt.connectionState} (${evt.timezoneId})",
            timestampMs = evt.detectedAtMs
        )
    }

    for (sess in networkSessions) {
        if (!selectedDeviceId.isNullOrBlank() && sess.deviceId != selectedDeviceId) continue
        val dur = sess.durationMs ?: (sess.lastVerifiedAtMs - sess.startedAtMs).coerceAtLeast(0L)
        items += UnifiedDeviceActivityItem(
            id = "ns_${sess.id}",
            deviceId = sess.deviceId,
            deviceName = sess.deviceName.ifBlank { devNameById[sess.deviceId] ?: sess.deviceId },
            kind = DeviceActivityKind.SESSION,
            title = "${sess.networkType} Session (${sess.status})",
            subtitle = "Duration: ${formatDurationShort(dur)}" +
                (sess.interruptionReason?.let { " • $it" } ?: ""),
            timestampMs = sess.endedAtMs ?: sess.startedAtMs
        )
    }

    for (loc in locations.takeLast(120)) {
        if (!selectedDeviceId.isNullOrBlank() && loc.deviceId != selectedDeviceId) continue
        items += UnifiedDeviceActivityItem(
            id = "loc_${loc.id}",
            deviceId = loc.deviceId,
            deviceName = devNameById[loc.deviceId] ?: loc.deviceId,
            kind = DeviceActivityKind.LOCATION,
            title = "Location Point (${loc.source})",
            subtitle = "${"%.5f, %.5f".format(Locale.US, loc.latitude, loc.longitude)} • ±${loc.accuracyMeters.toInt()}m • Bat ${loc.batteryPercent}% (${loc.networkType})",
            timestampMs = loc.recordedAtMs
        )
    }

    for (dev in targetDevices) {
        val statusTs = maxOf(dev.lastSeenAtMs, dev.lastSyncAtMs)
        if (statusTs > 0L) {
            items += UnifiedDeviceActivityItem(
                id = "dev_health_${dev.id}",
                deviceId = dev.id,
                deviceName = dev.deviceName,
                kind = DeviceActivityKind.BATTERY_STATUS,
                title = "Device Health: ${dev.deviceHealth} • Battery ${dev.batteryPercent}%",
                subtitle = "GPS=${if (dev.locationPermissionGranted) "OK" else "Missing"} • NetMon=${if (dev.networkMonitoringEnabled) "ON" else "OFF"} • ${dev.androidVersion}",
                timestampMs = statusTs
            )
        }
    }

    return items.sortedByDescending { it.timestampMs }
}

fun buildNetworkActivityCsv(
    deviceName: String,
    events: List<NetworkEventModel>,
    sessions: List<NetworkSessionModel>
): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    val sb = StringBuilder()
    sb.appendLine("# FamilyTrack BD - Network Activity Export ($deviceName)")
    sb.appendLine("RECORD_KIND,DEVICE,TYPE_OR_STATUS,NETWORK_TYPE,TIMESTAMP,DURATION_MS,TIMEZONE,DETAILS")
    for (s in sessions) {
        val dur = s.durationMs ?: (s.lastVerifiedAtMs - s.startedAtMs).coerceAtLeast(0L)
        sb.appendLine(
            listOf(
                "SESSION",
                csvEscape(s.deviceName),
                csvEscape(s.status),
                csvEscape(s.networkType),
                csvEscape(sdf.format(Date(s.startedAtMs))),
                dur.toString(),
                csvEscape(s.timezoneId),
                csvEscape(s.interruptionReason.orEmpty())
            ).joinToString(",")
        )
    }
    for (e in events) {
        sb.appendLine(
            listOf(
                "EVENT",
                csvEscape(e.deviceName),
                csvEscape(e.eventType),
                csvEscape(e.networkType),
                csvEscape(sdf.format(Date(e.detectedAtMs))),
                "",
                csvEscape(e.timezoneId),
                csvEscape(e.details.orEmpty())
            ).joinToString(",")
        )
    }
    return sb.toString()
}

fun buildLocationHistoryCsv(deviceName: String, records: List<LocationRecord>): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    val sb = StringBuilder()
    sb.appendLine("# FamilyTrack BD - 7-Day Route Export ($deviceName)")
    sb.appendLine("TIMESTAMP,LATITUDE,LONGITUDE,ACCURACY_M,BATTERY_PCT,NETWORK,SOURCE")
    for (r in records.sortedBy { it.recordedAtMs }) {
        sb.appendLine(
            listOf(
                csvEscape(sdf.format(Date(r.recordedAtMs))),
                r.latitude.toString(),
                r.longitude.toString(),
                r.accuracyMeters.toString(),
                r.batteryPercent.toString(),
                csvEscape(r.networkType),
                csvEscape(r.source)
            ).joinToString(",")
        )
    }
    return sb.toString()
}

fun buildExpensesCsv(expenses: List<FamilyExpense>): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val sb = StringBuilder()
    sb.appendLine("# FamilyTrack BD - Family Expenses (BDT)")
    sb.appendLine("DATE,TITLE,CATEGORY,AMOUNT_BDT,PAID_BY,NOTES")
    for (e in expenses.sortedByDescending { it.expenseDateMs }) {
        sb.appendLine(
            listOf(
                csvEscape(sdf.format(Date(e.expenseDateMs))),
                csvEscape(e.title),
                csvEscape(e.category),
                "%.2f".format(Locale.US, e.amountBdt),
                csvEscape(e.paidByName),
                csvEscape(e.notes)
            ).joinToString(",")
        )
    }
    return sb.toString()
}

fun computeExpenseCategoryTotals(expenses: List<FamilyExpense>, currentMonthOnly: Boolean = true): Map<String, Double> {
    val cal = Calendar.getInstance()
    val targetYear = cal.get(Calendar.YEAR)
    val targetMonth = cal.get(Calendar.MONTH)
    val filtered = if (!currentMonthOnly) expenses else expenses.filter { exp ->
        val c = Calendar.getInstance().apply { timeInMillis = exp.expenseDateMs }
        c.get(Calendar.YEAR) == targetYear && c.get(Calendar.MONTH) == targetMonth
    }
    val map = linkedMapOf<String, Double>()
    for (e in filtered) {
        map[e.category] = (map[e.category] ?: 0.0) + e.amountBdt
    }
    return map
}

private fun csvEscape(raw: String): String {
    val clean = raw.replace("\"", "\"\"").replace("\n", " ")
    return "\"$clean\""
}
