package com.flowai.communication.system.pet

/**
 * One desk-pet skin: a palette plus the accessory, tail and particle shapes that go with it.
 *
 * Colours are plain ARGB longs rather than Android colour objects, so the lookup and rotation
 * rules below stay unit-testable; the view turns them into paint when it draws. A skin costs a
 * palette, not an image asset, which is why the desk pet adds no APK weight.
 */
data class PetSkin(
    val id: String,
    val name: String,
    val description: String,
    val bodyColor: Long,
    val bodyShadowColor: Long,
    val earColor: Long,
    val cheekColor: Long,
    val eyeColor: Long,
    val auraColor: Long,
    val accessory: PetAccessory,
    val tail: PetTail,
    val particle: PetParticle
)

/** What sticks out of the pet's head; each skin picks one. */
enum class PetAccessory { ROUND_EARS, POINTED_EARS, LONG_EARS, ANTENNA, AHOGE }

/** What trails behind the body. */
enum class PetTail { NONE, FLUFFY, THIN }

/** The shape of the particles: click bursts and idle sparkles both use the skin's shape. */
enum class PetParticle { STAR, HEART, DOT, PETAL }

/**
 * The built-in wardrobe.
 *
 * Rotation is the only navigation the overlay offers (a long press), so the list order is the
 * cycle order and the first entry is the default.
 */
object PetSkins {

    const val DEFAULT_ID = "dango"

    val ALL: List<PetSkin> = listOf(
        PetSkin(
            id = "dango",
            name = "小蓝团",
            description = "FlowAI 的默认团子。圆耳朵，安静，但开心时会冒泡泡。",
            bodyColor = 0xFF5B7FE8,
            bodyShadowColor = 0xFF3D5AC7,
            earColor = 0xFF86A3FF,
            cheekColor = 0xFFFF9EB8,
            eyeColor = 0xFF1D2240,
            auraColor = 0xFFA8BEFF,
            accessory = PetAccessory.ROUND_EARS,
            tail = PetTail.NONE,
            particle = PetParticle.DOT
        ),
        PetSkin(
            id = "fox",
            name = "小狐",
            description = "机灵的小狐狸。尖耳朵和蓬松尾巴，点击时溅出星星。",
            bodyColor = 0xFFF59A3C,
            bodyShadowColor = 0xFFD97B1D,
            earColor = 0xFFF8B463,
            cheekColor = 0xFFFF8D7A,
            eyeColor = 0xFF3B2A18,
            auraColor = 0xFFFFC46B,
            accessory = PetAccessory.POINTED_EARS,
            tail = PetTail.FLUFFY,
            particle = PetParticle.STAR
        ),
        PetSkin(
            id = "mint",
            name = "薄荷",
            description = "薄荷色的小团子，头顶一根呆毛。像一杯冰饮，久看也不累。",
            bodyColor = 0xFF6FD3B0,
            bodyShadowColor = 0xFF4AB38F,
            earColor = 0xFF96E6C9,
            cheekColor = 0xFFFFA9C0,
            eyeColor = 0xFF17453A,
            auraColor = 0xFFA9EED8,
            accessory = PetAccessory.AHOGE,
            tail = PetTail.THIN,
            particle = PetParticle.DOT
        ),
        PetSkin(
            id = "sakura",
            name = "樱兔",
            description = "长耳朵的樱花兔子，耳朵会随动作轻晃，点击时落樱缤纷。",
            bodyColor = 0xFFF7A8C4,
            bodyShadowColor = 0xFFE27BA4,
            earColor = 0xFFFBC8DA,
            cheekColor = 0xFFFF7E9E,
            eyeColor = 0xFF55263A,
            auraColor = 0xFFFFC9DC,
            accessory = PetAccessory.LONG_EARS,
            tail = PetTail.NONE,
            particle = PetParticle.PETAL
        ),
        PetSkin(
            id = "starry",
            name = "星夜",
            description = "夜航的小星星。头顶天线，自带星屑，适合深夜聊天。",
            bodyColor = 0xFF4A55A2,
            bodyShadowColor = 0xFF333C78,
            earColor = 0xFF7B85D6,
            cheekColor = 0xFFC77FA8,
            eyeColor = 0xFF101228,
            auraColor = 0xFFB9A8FF,
            accessory = PetAccessory.ANTENNA,
            tail = PetTail.THIN,
            particle = PetParticle.STAR
        ),
        PetSkin(
            id = "panda",
            name = "团团",
            description = "黑白配色的团子。看着稳重，冒出来的却都是爱心。",
            bodyColor = 0xFFF3F4F8,
            bodyShadowColor = 0xFFD3D7E2,
            earColor = 0xFF23252E,
            cheekColor = 0xFFFFB0B8,
            eyeColor = 0xFF23252E,
            auraColor = 0xFFC9CEDC,
            accessory = PetAccessory.ROUND_EARS,
            tail = PetTail.NONE,
            particle = PetParticle.HEART
        )
    )

    private val lookup: Map<String, PetSkin> = ALL.associateBy { it.id }

    /** The skin with this id, or the default one when the id is unknown (e.g. left over from an older build). */
    fun byId(id: String?): PetSkin = lookup[id] ?: lookup.getValue(DEFAULT_ID)

    private fun indexOf(id: String?): Int =
        ALL.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            ?: ALL.indexOfFirst { it.id == DEFAULT_ID }

    /** The next skin in wardrobe order, wrapping around; used by the long-press cycle. */
    fun next(id: String?): PetSkin = ALL[(indexOf(id) + 1) % ALL.size]

    /** The previous skin in wardrobe order; kept for symmetry with [next]. */
    fun previous(id: String?): PetSkin = ALL[(indexOf(id) - 1 + ALL.size) % ALL.size]
}
