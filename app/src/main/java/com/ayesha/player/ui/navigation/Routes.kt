package com.ayesha.player.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose routes. Screens are added phase by phase (see MIGRATION_PLAN.md section 5). */
sealed interface Route {
    @Serializable data object Home : Route

    @Serializable data class FolderVideos(val folderPath: String) : Route

    /**
     * [folderPath]: the library folder used as the playlist (next/previous, auto-play next);
     * null for a single file. [resumeMs]: where to start, or null for the beginning.
     */
    @Serializable
    data class Player(
        val path: String,
        val name: String,
        val resumeMs: Long? = null,
        val folderPath: String? = null,
    ) : Route

    /** Destination picker for move/copy. Returns the chosen path via [PICKED_FOLDER_KEY]. */
    @Serializable
    data class FolderPicker(val excludePath: String, val title: String, val confirmLabel: String) : Route

    /**
     * The vault PIN pad. [mode]: "create" or "unlock". [next]: what success leads to: "open"
     * (the vault), "change" (verified: now create the new PIN), "changed" (new PIN saved: back
     * to the vault, reporting via [PIN_CHANGED_KEY]).
     */
    @Serializable data class VaultPin(val mode: String, val next: String) : Route

    @Serializable data object Vault : Route

    companion object {
        /** SavedStateHandle key the folder picker writes its result to. */
        const val PICKED_FOLDER_KEY = "picked_folder"

        /** SavedStateHandle key: the vault PIN was just changed. */
        const val PIN_CHANGED_KEY = "pin_changed"

        /** Route.Player.folderPath meaning "the vault is the playlist" (can't clash with a real path). */
        const val VAULT_PLAYLIST = "vault://playlist"
    }
}
