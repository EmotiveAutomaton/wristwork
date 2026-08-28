package com.emotiveautomaton.wristwork.data

/**
 * Canonical Panksepp vocabulary lives in the data forever (CLAUDE.md rule); the face and grid
 * show humane, deniable display names (HEALTH_DESIGN.md — owner strikes/replaces freely).
 * Order is the owner's and is not regrouped.
 */
object StateNames {
    val CANONICAL = listOf("SEEK", "RAGE", "FEAR", "LUST", "CARE", "GRIEF", "PLAY", "OTHER")

    // Owner rollback 2026-08-24: canonical words are fine in public for most states; only the
    // three that read badly keep display names (RAGE->Vexed, FEAR->Tense, LUST->Heat).
    private val display = mapOf(
        "RAGE" to "Vexed",
        "FEAR" to "Tense",
        "LUST" to "Heat",
        // 2026-08-28, owner: the eighth slot is NEUTRAL, not a catch-all. "I don't think I'll ever
        // have an other, since I have secondaries I can mix — but I do think I will have a
        // neutral." The canonical word in the data stays OTHER (the vocabulary is permanent and
        // the archive is immutable); only the meaning on the wrist changed, and it is recorded
        // here and in the spec so nobody later reads old rows as if they meant the new thing.
        "OTHER" to "Neutral",
    )

    fun humane(canonical: String): String =
        display[canonical] ?: canonical.lowercase().replaceFirstChar { it.uppercase() }

    /** True when the grid should show the canonical word in small type beneath the display
     *  name. That is for the three DENIABLE renames — someone reading over a shoulder sees
     *  "Vexed" but the owner can still find RAGE. Neutral is a meaning change, not a disguise,
     *  so it stands alone. */
    fun renamed(canonical: String): Boolean = canonical in display && canonical != "OTHER"
}
