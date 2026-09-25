package com.flowai.communication.system.pet

import android.content.Context

/**
 * Where the chosen skin is kept, so it survives a service restart.
 *
 * Kept behind an interface because the only value worth persisting is an id; unknown ids are
 * normalised to the default on the way in AND on the way out, which is what makes an upgrade
 * (or a downgrade) safe when the wardrobe changes.
 */
interface PetSkinStore {
    fun load(): String
    fun save(id: String)
}

/** Session-only store for tests. */
class InMemoryPetSkinStore(initial: String? = null) : PetSkinStore {
    private var id: String = PetSkins.byId(initial).id

    override fun load(): String = id

    override fun save(id: String) {
        this.id = PetSkins.byId(id).id
    }
}

/** Persists the skin id in the app's private preferences. */
class PrefsPetSkinStore(context: Context) : PetSkinStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): String = PetSkins.byId(prefs.getString(KEY_SKIN, null)).id

    override fun save(id: String) {
        prefs.edit().putString(KEY_SKIN, PetSkins.byId(id).id).apply()
    }

    private companion object {
        const val PREFS_NAME = "flowai.pet"
        const val KEY_SKIN = "skinId"
    }
}
