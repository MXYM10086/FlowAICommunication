package com.flowai.communication.skill
import com.flowai.communication.data.model.ActionType
interface Skill { val id: String; val supportedTypes: Set<ActionType> }
