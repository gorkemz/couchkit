// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

/**
 * Experimental edition only (never a release build): device batteries through an unofficial,
 * undocumented web API. See extras/experimental-batteries-bridge/README.md for the risks.
 */
object BatterySources {
    val active: BatterySource = CloudBatteries
}
