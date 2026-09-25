package com.flowai.communication.system.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wardrobe's lookup and rotation rules.
 *
 * These are the parts a long press and a stored id depend on, and they are deliberately free of
 * Android APIs so they run as plain JVM tests.
 */
class PetSkinTest {

    @Test
    fun `every skin has a unique id and a non-blank name`() {
        val ids = PetSkins.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(PetSkins.ALL.all { it.name.isNotBlank() && it.description.isNotBlank() })
    }

    @Test
    fun `the default skin exists in the wardrobe`() {
        assertTrue(PetSkins.ALL.any { it.id == PetSkins.DEFAULT_ID })
    }

    @Test
    fun `unknown ids fall back to the default skin`() {
        assertEquals(PetSkins.DEFAULT_ID, PetSkins.byId("does-not-exist").id)
        assertEquals(PetSkins.DEFAULT_ID, PetSkins.byId(null).id)
    }

    @Test
    fun `next visits every skin and wraps around`() {
        var id = PetSkins.DEFAULT_ID
        val seen = mutableSetOf(id)
        repeat(PetSkins.ALL.size - 1) {
            id = PetSkins.next(id).id
            seen += id
        }
        assertEquals(PetSkins.ALL.size, seen.size)
        assertEquals(PetSkins.DEFAULT_ID, PetSkins.next(id).id)
    }

    @Test
    fun `previous is the inverse of next`() {
        PetSkins.ALL.forEach { skin ->
            assertEquals(skin.id, PetSkins.previous(PetSkins.next(skin.id).id).id)
        }
    }

    @Test
    fun `next from an unknown id starts at the skin after the default`() {
        assertEquals(PetSkins.next(PetSkins.DEFAULT_ID).id, PetSkins.next("gone-in-a-newer-build").id)
    }

    @Test
    fun `in-memory store keeps and normalises the choice`() {
        val store = InMemoryPetSkinStore()
        assertEquals(PetSkins.DEFAULT_ID, store.load())

        store.save("fox")
        assertEquals("fox", store.load())

        // An id the wardrobe does not know must not leak out of the store.
        store.save("nonsense")
        assertEquals(PetSkins.DEFAULT_ID, store.load())
    }

    @Test
    fun `in-memory store normalises its initial value`() {
        assertEquals(PetSkins.DEFAULT_ID, InMemoryPetSkinStore("nonsense").load())
        assertEquals("sakura", InMemoryPetSkinStore("sakura").load())
    }
}
