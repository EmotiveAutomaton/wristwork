package com.emotiveautomaton.wristwork.data

/**
 * Canonical Panksepp vocabulary lives in the data forever (CLAUDE.md rule); the face and grid
 * show humane, deniable display names (HEALTH_DESIGN.md — owner strikes/replaces freely).
 * Order is the owner's and is not regrouped.
 */
object StateNames {
    val CANONICAL = listOf("SEEK", "RAGE", "FEAR", "LUST", "CARE", "GRIEF", "PLAY", "OTHER")

    private val display = mapOf(
        "SEEK" to "Drive",
        "RAGE" to "Vexed",
        "FEAR" to "Tense",
        "LUST" to "Heat",
        "CARE" to "Warm",
        "GRIEF" to "Heavy",
        "PLAY" to "Light",
        "OTHER" to "Odd",
    )

    fun humane(canonical: String): String = display[canonical] ?: canonical
}
