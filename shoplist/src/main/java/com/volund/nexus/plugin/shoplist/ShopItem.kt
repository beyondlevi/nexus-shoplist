package com.volund.nexus.plugin.shoplist

/**
 * A single shopping-list entry. Immutable; toggling `done` produces a copy.
 *
 * [id] is a stable opaque key used for persistence and for computing the
 * surface contentKey — never rendered to the user.
 */
data class ShopItem(
    val id: String,
    val label: String,
    val done: Boolean = false,
)
