package com.flowai.communication.skill
import com.flowai.communication.data.model.ActionType
class SkillRegistry(private val skills: List<Skill> = emptyList()) {
    fun find(type: ActionType): Skill? = skills.firstOrNull { type in it.supportedTypes }
}
