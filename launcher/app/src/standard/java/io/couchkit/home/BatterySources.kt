// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context

/** Standard edition: no battery source. The battery pill never shows and nothing is requested. */
object BatterySources {
    val active: BatterySource = object : BatterySource {
        override val refreshMs = 24 * 3_600_000L
        override fun cached(ctx: Context) = emptyList<BatterySource.Device>()
        override fun isStale(ctx: Context) = false
        override fun hasPendingImport(ctx: Context) = false
        override fun fetch(ctx: Context): List<BatterySource.Device>? = emptyList()
    }
}
