package com.zzl.guardian.child.dns

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PrivateDnsPolicy] 判定树的纯 JVM 单测。
 *
 * 覆盖四种病因/状态各自的处置，以及它们之间的边界。
 */
class PrivateDnsPolicyTest {

    private val HOST = "dns.example.com"

    private fun inputs(
        expected: String? = HOST,
        current: String? = HOST,
        canaryFailures: Int = 0,
        dotReachable: Boolean? = null,
        networkUp: Boolean = true,
        failOpen: Boolean = false,
    ) = PrivateDnsPolicy.Inputs(
        expected = expected,
        current = current,
        canaryFailures = canaryFailures,
        dotReachable = dotReachable,
        networkUp = networkUp,
        failOpen = failOpen,
    )

    @Test
    fun `一切正常时保持现状`() {
        assertEquals(PrivateDnsPolicy.Action.KEEP, PrivateDnsPolicy.decide(inputs()))
    }

    @Test
    fun `设备离线时什么都不做`() {
        assertEquals(
            PrivateDnsPolicy.Action.NONE,
            PrivateDnsPolicy.decide(inputs(networkUp = false)),
        )
        // 即使被篡改，离线时也无法判断（网络不可用可能就是"被改"的原因）
        assertEquals(
            PrivateDnsPolicy.Action.NONE,
            PrivateDnsPolicy.decide(inputs(current = null, networkUp = false)),
        )
    }

    @Test
    fun `设置被篡改时写回`() {
        assertEquals(
            PrivateDnsPolicy.Action.WRITE,
            PrivateDnsPolicy.decide(inputs(current = null)),
        )
        assertEquals(
            PrivateDnsPolicy.Action.WRITE,
            PrivateDnsPolicy.decide(inputs(current = "evil.dns.example.net")),
        )
    }

    @Test
    fun `初次启用时写入`() {
        assertEquals(
            PrivateDnsPolicy.Action.WRITE,
            PrivateDnsPolicy.decide(inputs(current = null, dotReachable = null)),
        )
    }

    @Test
    fun `家长停用后设备残留应清除`() {
        assertEquals(
            PrivateDnsPolicy.Action.WRITE,
            PrivateDnsPolicy.decide(
                inputs(expected = null, current = HOST),
            ),
        )
    }

    @Test
    fun `解析连续失败且服务不可达时触发自愈`() {
        assertEquals(
            PrivateDnsPolicy.Action.FAIL_OPEN,
            PrivateDnsPolicy.decide(
                inputs(canaryFailures = PrivateDnsPolicy.CANARY_FAIL_LIMIT, dotReachable = false),
            ),
        )
        // 失败次数不足：继续观察，不轻易降级
        assertEquals(
            PrivateDnsPolicy.Action.KEEP,
            PrivateDnsPolicy.decide(
                inputs(canaryFailures = PrivateDnsPolicy.CANARY_FAIL_LIMIT - 1, dotReachable = false),
            ),
        )
        // 解析失败但 853 可达：不是服务挂了（可能是金丝雀误报），保持
        assertEquals(
            PrivateDnsPolicy.Action.KEEP,
            PrivateDnsPolicy.decide(
                inputs(canaryFailures = PrivateDnsPolicy.CANARY_FAIL_LIMIT, dotReachable = true),
            ),
        )
    }

    @Test
    fun `降级状态下等待服务恢复`() {
        // fail-open 后 current 已被清空，但必须抑制"写回"冲动，等服务真恢复
        assertEquals(
            PrivateDnsPolicy.Action.KEEP,
            PrivateDnsPolicy.decide(
                inputs(current = null, dotReachable = false, failOpen = true),
            ),
        )
        assertEquals(
            PrivateDnsPolicy.Action.KEEP,
            PrivateDnsPolicy.decide(
                inputs(current = null, dotReachable = null, failOpen = true),
            ),
        )
    }

    @Test
    fun `服务恢复后从降级状态自动还原`() {
        assertEquals(
            PrivateDnsPolicy.Action.WRITE,
            PrivateDnsPolicy.decide(
                inputs(current = null, dotReachable = true, failOpen = true),
            ),
        )
    }

    @Test
    fun `未启用防护时无事可做`() {
        assertEquals(
            PrivateDnsPolicy.Action.KEEP,
            PrivateDnsPolicy.decide(inputs(expected = null, current = null)),
        )
    }
}
