package com.zzl.guardian.child.screenshot

/**
 * 截屏图片的缩放与质量决策。
 *
 * 刻意做成**纯 JVM 代码**（不碰 android.graphics），因为这几个数值决定了
 * 「截图能不能看清」与「流量会不会失控」之间的平衡，是最该被测试覆盖的部分，
 * 而真机上验证一次的成本很高（要真的截一张再肉眼比对）。
 */
object ImageScaling {

    /** 长边上限：超过则等比缩小。1920 足够看清孩子在用哪个应用、页面大致内容 */
    const val MAX_LONG_SIDE = 1920

    /** 再小也不低于这个尺寸 —— 否则截图变得毫无信息量 */
    const val MIN_LONG_SIDE = 320

    /** JPEG 质量。0.7 是「看得清文字」与「体积可接受」的常见平衡点 */
    const val DEFAULT_QUALITY = 70

    /** 质量的可调范围：低于 40 文字开始糊，高于 85 体积增长过快 */
    const val MIN_QUALITY = 40
    const val MAX_QUALITY = 85

    data class Plan(
        val width: Int,
        val height: Int,
        val quality: Int,
        /** 是否真的缩小了。用于日志与回执，让"为什么这张很小"可解释 */
        val scaled: Boolean,
    )

    /**
     * 计算缩放方案。
     *
     * 只缩不放：原图比上限小时保持原样 —— 放大不会增加信息量，
     * 只会凭空撑大体积。
     */
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        maxLongSide: Int = MAX_LONG_SIDE,
        quality: Int = DEFAULT_QUALITY,
    ): Plan {
        val safeQuality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)

        if (sourceWidth <= 0 || sourceHeight <= 0) {
            // 拿不到尺寸时不做缩放，交给压缩环节尽力而为。
            // 返回 0 会让调用方以为"尺寸未知可以跳过"，反而更容易出错。
            return Plan(sourceWidth.coerceAtLeast(0), sourceHeight.coerceAtLeast(0), safeQuality, false)
        }

        val longSide = maxOf(sourceWidth, sourceHeight)

        // 上限本身也要夹住：传进来一个 100 的上限会让截图彻底不可读
        val limit = maxLongSide.coerceAtLeast(MIN_LONG_SIDE)

        if (longSide <= limit) {
            return Plan(sourceWidth, sourceHeight, safeQuality, false)
        }

        val ratio = limit.toDouble() / longSide
        // 至少保留 1 像素：极端长条形（如 10000x1）缩完会出现 0
        val targetWidth = (sourceWidth * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * ratio).toInt().coerceAtLeast(1)

        return Plan(targetWidth, targetHeight, safeQuality, true)
    }

    /**
     * 依据实际字节数决定是否需要重压。
     *
     * 服务端有 1 MB 上限，超了会被拒收。与其让家长看到"上传失败"，
     * 不如在设备侧主动降一次质量 —— 质量 50 的截图对"看孩子在用哪个应用"
     * 这个目的完全够用。
     *
     * @return 重压后的质量；null 表示当前体积已可接受
     */
    fun qualityForSize(byteSize: Int, limitBytes: Int): Int? {
        if (byteSize <= limitBytes) return null

        // 按超出比例线性降质，并夹在可读范围内。
        // 不做多轮试探：截屏是即时交互，多压几次会让家长等太久。
        val overflowRatio = byteSize.toDouble() / limitBytes
        val target = when {
            overflowRatio > 8.0 -> MIN_QUALITY
            overflowRatio > 4.0 -> 45
            overflowRatio > 2.0 -> 55
            else -> 60
        }
        return target.coerceIn(MIN_QUALITY, MAX_QUALITY)
    }
}
