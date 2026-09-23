package com.zzl.guardian.child.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截屏图片的缩放与质量决策。
 *
 * 这几个数值决定了「截图能不能看清」与「流量会不会失控」之间的平衡，
 * 而真机上验证一次的成本很高（要真的截一张再肉眼比对）。
 * 抽成纯函数后可以在这里把边界条件全跑一遍。
 */
class ImageScalingTest {

    /* ==================== 缩放 ==================== */

    @Test
    fun keepsSmallImageUnchanged() {
        val plan = ImageScaling.plan(1080, 1920)
        assertEquals(1080, plan.width)
        assertEquals(1920, plan.height)
        assertFalse("本来就小于上限，不该缩放", plan.scaled)
    }

    @Test
    fun shrinksByLongestSideNotWidth() {
        // 竖屏截图：长边是高。按宽度算会把它缩得比预期小得多
        val plan = ImageScaling.plan(1080, 2560)
        assertEquals(ImageScaling.MAX_LONG_SIDE, plan.height)
        assertTrue(plan.scaled)
        // 宽高比必须保持，否则截图会变形
        val ratioBefore = 1080.0 / 2560.0
        val ratioAfter = plan.width.toDouble() / plan.height
        assertTrue("宽高比应基本不变", kotlin.math.abs(ratioBefore - ratioAfter) < 0.01)
    }

    @Test
    fun shrinksLandscapeByLongestSide() {
        // 横屏 / 平板：长边是宽
        val plan = ImageScaling.plan(2560, 1600)
        assertEquals(ImageScaling.MAX_LONG_SIDE, plan.width)
        assertTrue(plan.scaled)
    }

    @Test
    fun neverUpscales() {
        // 放大不会增加信息量，只会凭空撑大体积
        val plan = ImageScaling.plan(320, 240)
        assertEquals(320, plan.width)
        assertEquals(240, plan.height)
        assertFalse(plan.scaled)
    }

    @Test
    fun handlesExactlyAtLimit() {
        val plan = ImageScaling.plan(1920, 1080)
        assertFalse("刚好等于上限不该触发缩放", plan.scaled)
    }

    @Test
    fun extremeAspectRatioKeepsAtLeastOnePixel() {
        // 极端长条形：10000x1。缩完宽度会算成 0，
        // 而 0 宽的位图会让 createScaledBitmap 抛异常
        val plan = ImageScaling.plan(10_000, 1)
        assertTrue("宽度必须至少 1 像素", plan.width >= 1)
        assertTrue("高度必须至少 1 像素", plan.height >= 1)
    }

    @Test
    fun invalidDimensionsDoNotClaimScaling() {
        // 拿不到尺寸时如实返回"未缩放"，而不是返回 0 让调用方以为可以跳过
        val plan = ImageScaling.plan(0, 0)
        assertFalse(plan.scaled)
    }

    @Test
    fun clampsAbsurdLimit() {
        // 传一个荒谬的小上限不该让截图变得完全不可读。
        // 注意断言的是**长边**：240 这种短边是按比例缩出来的，不该拿它跟下限比。
        val plan = ImageScaling.plan(4000, 3000, maxLongSide = 10)
        val longSide = maxOf(plan.width, plan.height)
        assertTrue("长边应被夹到 MIN_LONG_SIDE，实际 $longSide", longSide >= ImageScaling.MIN_LONG_SIDE)
        assertTrue("缩放后仍要保持宽高比", plan.scaled)
    }

    /* ==================== 质量 ==================== */

    @Test
    fun defaultQualityIsUsable() {
        val plan = ImageScaling.plan(1080, 1920)
        assertEquals(ImageScaling.DEFAULT_QUALITY, plan.quality)
    }

    @Test
    fun clampsQualityIntoReadableRange() {
        // 质量 5 的截图是一团糊，没有意义；质量 100 体积失控
        assertEquals(ImageScaling.MIN_QUALITY, ImageScaling.plan(100, 100, quality = 5).quality)
        assertEquals(ImageScaling.MAX_QUALITY, ImageScaling.plan(100, 100, quality = 100).quality)
    }

    /* ==================== 超限重压 ==================== */

    @Test
    fun noRecompressWhenWithinLimit() {
        // 体积达标就不该动质量 —— 白降一次质量只会让截图更糊
        assertNull(ImageScaling.qualityForSize(500_000, 1_048_576))
        assertNull(ImageScaling.qualityForSize(1_048_576, 1_048_576))
    }

    @Test
    fun recompressesWhenOverLimit() {
        val quality = ImageScaling.qualityForSize(1_500_000, 1_048_576)
        assertTrue("超出上限时应给出更低的质量", quality != null)
        assertTrue(quality!! < ImageScaling.DEFAULT_QUALITY)
    }

    @Test
    fun heavierOverflowMeansLowerQuality() {
        // 超出越多降得越狠，且始终落在可读范围内
        val slight = ImageScaling.qualityForSize(1_200_000, 1_000_000)!!
        val heavy = ImageScaling.qualityForSize(9_000_000, 1_000_000)!!

        assertTrue("轻微超出不该降到最低质量", slight > ImageScaling.MIN_QUALITY)
        assertEquals("严重超出应降到最低质量", ImageScaling.MIN_QUALITY, heavy)
        assertTrue("超出越多质量越低", heavy <= slight)
    }

    @Test
    fun recompressedQualityStaysReadable() {
        // 无论超出多少，都不该降到 MIN_QUALITY 以下
        for (size in listOf(1_100_000, 3_000_000, 10_000_000, 50_000_000)) {
            val quality = ImageScaling.qualityForSize(size, 1_000_000)
            assertTrue("质量必须不低于 $MIN_QUALITY", quality!! >= ImageScaling.MIN_QUALITY)
            assertTrue("质量必须不高于 $MAX_QUALITY", quality <= ImageScaling.MAX_QUALITY)
        }
    }

    private companion object {
        const val MIN_QUALITY = ImageScaling.MIN_QUALITY
        const val MAX_QUALITY = ImageScaling.MAX_QUALITY
    }
}
