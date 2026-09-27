package com.zzl.guardian.child.dns

/**
 * 私人 DNS 防护的判定树（纯函数，可 JVM 单测）。
 *
 * 三种病因必须区分开，处置完全不同：
 *
 * | 现象 | 病因 | 处置 |
 * |---|---|---|
 * | 设置被改掉/关闭，但网络本身通 | 孩子篡改 | 写回期望值 + 审计告警 |
 * | 设置正确，但解析持续失败、DoT 端口不可达 | 过滤服务故障（AGH 宕机） | **fail-open**：临时清除恢复上网 + 告警，服务恢复后自动重新启用 |
 * | 网络整体不可用 | 设备离线 | 什么都不做（此时做任何判断都是误判） |
 *
 * 关键的参照物是 **DoT 端口探测**：直接对期望主机的 853 端口发起 TCP 连接。
 * 它不经过 DNS（避免被故障本身污染），能可靠区分「过滤服务挂了」和「设备没网」。
 */
object PrivateDnsPolicy {

    /** 金丝雀解析连续失败多少次才判定为故障（30 秒一轮，3 次 ≈ 1.5 分钟） */
    const val CANARY_FAIL_LIMIT = 3

    enum class Action {
        /** 什么都不做 */
        NONE,
        /** 写入期望值（覆盖篡改 / 初次生效 / 从 fail-open 恢复） */
        WRITE,
        /** 保持现状（等待下一轮探测） */
        KEEP,
        /** 故障自愈：临时清除私人 DNS，恢复设备上网 */
        FAIL_OPEN,
    }

    data class Inputs(
        /** 家长期望的主机名（null = 未启用防护） */
        val expected: String?,
        /** 设备当前实际的私人 DNS 主机名（null = 未设置） */
        val current: String?,
        /** 金丝雀域名解析连续失败次数 */
        val canaryFailures: Int,
        /** 期望主机 853 端口可达性（null = 尚未探测出结果） */
        val dotReachable: Boolean?,
        /** 设备是否有可用网络（不看 DNS，只看 IP 层） */
        val networkUp: Boolean,
        /** 当前是否已处于 fail-open 降级状态 */
        val failOpen: Boolean,
    )

    fun decide(i: Inputs): Action = when {
        // 设备离线时一切探测结果都不可信，也不存在"篡改"的可能
        !i.networkUp -> Action.NONE

        // 已处于降级状态：只等过滤服务恢复（853 重新可达）再写回
        i.failOpen -> if (i.expected != null && i.dotReachable == true) Action.WRITE else Action.KEEP

        // 设置与期望不一致：篡改或初次生效，一律写回（写回成本低，且写错也只是恢复原状）
        i.expected != i.current -> Action.WRITE

        // 设置正确但解析持续失败、且过滤服务确实不可达 → 故障，自愈
        i.expected != null &&
            i.canaryFailures >= CANARY_FAIL_LIMIT &&
            i.dotReachable == false -> Action.FAIL_OPEN

        else -> Action.KEEP
    }
}
