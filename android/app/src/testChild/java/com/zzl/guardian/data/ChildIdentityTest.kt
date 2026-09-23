package com.zzl.guardian.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 设备身份派生测试。
 *
 * `deriveUuid` 决定「卸载重装后重新配对，控制端是复用原条目还是多出一条」：
 * 派生不稳定 = 同一台平板在家长列表里变成两台，这正是要防的回归。
 */
class ChildIdentityTest {

    @Test
    fun sameAndroidIdDerivesTheSameUuid() {
        val first = SettingsStore.deriveUuid("af019c8b771234de")
        val second = SettingsStore.deriveUuid("af019c8b771234de")
        assertEquals(first, second)
    }

    @Test
    fun differentAndroidIdsDeriveDifferentUuids() {
        val a = SettingsStore.deriveUuid("aaaa111122223333")
        val b = SettingsStore.deriveUuid("bbbb111122223333")
        assertNotEquals(a, b)
    }

    @Test
    fun missingAndroidIdFallsBackToRandom() {
        for (bad in listOf<String?>(null, "", "   ", "9774d56d682e549c")) {
            val first = SettingsStore.deriveUuid(bad)
            val second = SettingsStore.deriveUuid(bad)
            // 回退路径必须是随机值：两次调用不同，且都是合法 UUID
            assertNotEquals("输入 [$bad] 应回退为随机 UUID", first, second)
            assertParsesAsUuid(first)
            assertParsesAsUuid(second)
        }
    }

    @Test
    fun derivedValueIsAWellFormedUuid() {
        assertParsesAsUuid(SettingsStore.deriveUuid("0123456789abcdef"))
    }

    private fun assertParsesAsUuid(value: String) {
        UUID.fromString(value)
        assertTrue("应为 36 位标准 UUID：$value", value.length == 36)
    }
}
