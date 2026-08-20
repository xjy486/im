package com.jitong.im.android.push

import android.os.Bundle

internal data class NotificationPayload(
    val version: Int,
    val type: String,
) {
    companion object {
        fun from(data: Map<String, String>): NotificationPayload? {
            val type = data["type"] ?: return null
            if (data["version"] != "1") return null
            if (type != "NEW_MESSAGE") return null
            return NotificationPayload(
                version = 1,
                type = type,
            )
        }

        fun from(bundle: Bundle): NotificationPayload? =
            from(bundle.keySet().associateWith { key -> bundle.getString(key).orEmpty() })
    }
}
