package com.flowai.communication
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import com.flowai.communication.ai.MockLlmService
import com.flowai.communication.data.model.*
import com.flowai.communication.data.repository.*
import com.flowai.communication.domain.PlainTextDialogueParser
import com.flowai.communication.system.*

class FlowEngineTest {
    private val parser = PlainTextDialogueParser()
    private val mock = MockLlmService()
    private fun repo() = ConversationRepository(parser, mock, mock, mock, mock)
    @Test fun parsesDemoAInOrder() {
        val messages = parser.parse(DemoConversations.A)
        assertEquals(listOf(Speaker.OTHER, Speaker.ME, Speaker.OTHER), messages.map { it.speaker })
        assertEquals(listOf(0, 1, 2), messages.map { it.order })
        assertEquals(3, messages.map { it.id }.distinct().size)
    }
    @Test fun supportsAsciiColonAndAdditionalSpeakers() {
        val messages = parser.parse("小王: 你好\n小李：好的\n小张：收到\n我：确认")
        assertEquals(listOf(Speaker.OTHER, Speaker.OTHER_2, Speaker.UNKNOWN, Speaker.ME), messages.map { it.speaker })
    }
    @Test fun preservesUnlabelledTimesAndUrls() {
        val messages = parser.parse("15:00 开会\nhttps://example.com\n补充说明")
        assertTrue(messages.all { it.speaker == Speaker.UNKNOWN })
        assertEquals("15:00 开会", messages.first().text)
    }
    @Test fun skipsBlankMessages() { assertTrue(parser.parse("  \n我：  \r\n").isEmpty()) }
    @Test fun rejectsEmptyInput() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo().analyze(" ") } }
    }
    @Test fun rejectsOversizeInput() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo().analyze("字".repeat(20_001)) } }
    }
    @Test fun demoAHasExpectedStateAndTopThree() = runTest {
        val result = repo().analyze(DemoConversations.A)
        assertEquals("任务完成进度", result.state.topic)
        assertTrue(result.state.unresolvedIssues.contains("尚未明确具体完成时间"))
        assertEquals(listOf(ActionType.GIVE_DEADLINE, ActionType.REPORT_PROGRESS, ActionType.CLARIFY), result.actions.map { it.type })
        assertEquals(listOf(1, 2, 3), result.actions.map { it.priority })
        assertTrue(result.state.agreements.isEmpty())
    }
    @Test fun demoAProducesThreeReplyStyles() = runTest {
        val repository = repo(); val result = repository.analyze(DemoConversations.A)
        val output = repository.execute(result, result.actions.first())
        assertEquals(listOf("自然", "简洁", "正式"), output.replies.map { it.style })
        assertTrue(output.replies.all { "今晚十点" in it.text })
        assertTrue(output.objects.isEmpty())
    }
    @Test fun eachDemoAActionProducesDifferentReplies() = runTest {
        val repository = repo(); val result = repository.analyze(DemoConversations.A)
        val distinct = result.actions.map { repository.execute(result, it).replies }.distinct()
        assertEquals(3, distinct.size)
    }
    @Test fun demoBProducesOneEventAndTwoTasks() = runTest {
        val repository = repo(); val result = repository.analyze(DemoConversations.B)
        val output = repository.execute(result, result.actions.first())
        val event = output.objects.filterIsInstance<ActionObject.Event>().single()
        assertEquals("明天 15:00", event.time); assertEquals("A203", event.location)
        val tasks = output.objects.filterIsInstance<ActionObject.Task>()
        assertEquals(listOf("小王", "小李"), tasks.map { it.assignee })
        assertEquals(listOf("准备 PPT", "整理数据"), tasks.map { it.title })
        assertTrue(tasks.all { it.deadline == "今晚 22:00" })
        assertEquals(3, output.objects.size)
    }
    @Test fun eventActionOnlyProducesEvent() = runTest {
        val repository = repo(); val result = repository.analyze(DemoConversations.B)
        val output = repository.execute(result, result.actions[1])
        assertEquals(1, output.objects.size); assertTrue(output.objects.single() is ActionObject.Event)
    }
    @Test fun arbitraryTextDoesNotInventDemoFacts() = runTest {
        val repository = repo(); val result = repository.analyze("我：下周讨论旅行吧")
        assertEquals(ConversationStage.UNKNOWN, result.state.stage)
        assertTrue(repository.execute(result, result.actions.last()).objects.isEmpty())
        assertFalse(result.state.keyFacts.any { "A203" in it })
    }
    @Test fun alteredDemoDoesNotReturnStaleTime() = runTest {
        val result = repo().analyze(DemoConversations.B.replace("三点", "四点"))
        assertEquals(ConversationStage.UNKNOWN, result.state.stage)
    }
    @Test fun analysesAreIndependentWithoutRecentHistory() = runTest {
        val repository = repo()
        val first = repository.analyze(DemoConversations.A)
        val second = repository.analyze(DemoConversations.B)
        assertEquals("任务完成进度", first.state.topic)
        assertEquals("组会安排与材料准备", second.state.topic)
        assertFalse(second.capsule.messages.any { "行吧" in it.text })
    }
    @Test fun modelsPreserveSourceAndNullableFields() {
        val capsule = ContextCapsule(SourceType.TEXT, messages = parser.parse(DemoConversations.A), timestamp = 42)
        assertEquals(42L, capsule.timestamp); assertNull(capsule.appName)
        assertEquals(capsule, capsule.copy())
        assertNull(ActionObject.Task("任务", null, null, null).deadline)
        assertEquals("确认", ActionObject.Decision("确认").content)
    }
    @Test fun allSystemProvidersAreDisabled() {
        assertNull(StubOverlayContextProvider().capture())
        assertNull(StubAccessibilityContextProvider().capture())
        assertNull(StubScreenCaptureProvider().capture())
        assertFalse(StubShareReceiver().receive("text"))
    }
    @Test fun rejectsForeignAction() = runTest {
        val repository = repo(); val result = repository.analyze(DemoConversations.A)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.execute(result, result.actions.first().copy(id = "foreign")) }
        }
    }
    @Test fun shareSourceIsPreservedInCapsule() = runTest {
        val result = repo().analyze(DemoConversations.A, SourceType.SHARE)
        assertEquals(SourceType.SHARE, result.capsule.sourceType)
    }
}
