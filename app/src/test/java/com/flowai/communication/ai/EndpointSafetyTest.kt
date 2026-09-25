package com.flowai.communication.ai

import java.net.URL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 明文端点防护单测（[isSecureOrLocal] / [isLoopbackOrPrivate]）。
 *
 * 远程分析引擎与回复卡片客户端共用这套判断；DEBUG 构建下只放行回环 / 私网 /
 * 模拟器宿主机别名，release 一律仅 https（BuildConfig.DEBUG 在 testDebug 下为真）。
 */
class EndpointSafetyTest {

    @Test
    fun httpsAlwaysAllowed() {
        assertTrue(URL("https://api.deepseek.com/chat/completions").isSecureOrLocal())
    }

    @Test
    fun cleartextAllowedOnlyForLoopbackAndPrivateRanges() {
        assertTrue(URL("http://localhost:11434/v1/chat/completions").isSecureOrLocal())
        assertTrue(URL("http://127.0.0.1:8123/v1/chat/completions").isSecureOrLocal())
        // The emulator's alias for the host machine.
        assertTrue(URL("http://10.0.2.2:8124/v1/chat/completions").isSecureOrLocal())
        assertTrue(URL("http://192.168.1.20/x").isSecureOrLocal())
        assertTrue(URL("http://172.16.5.5/x").isSecureOrLocal())
        assertTrue(URL("http://172.31.5.5/x").isSecureOrLocal())
    }

    @Test
    fun cleartextRefusedForPublicAndOutOfRangeHosts() {
        assertFalse(URL("http://example.com/chat/completions").isSecureOrLocal())
        assertFalse(URL("http://8.8.8.8/x").isSecureOrLocal())
        assertFalse(URL("http://172.32.5.5/x").isSecureOrLocal())
        assertFalse(URL("http://192.169.5.5/x").isSecureOrLocal())
        assertFalse(URL("http://11.0.0.2/x").isSecureOrLocal())
    }

    @Test
    fun nonHttpProtocolsRefused() {
        assertFalse(URL("ftp://localhost/x").isSecureOrLocal())
    }
}
