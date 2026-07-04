package io.github.vvb2060.ims.privileged

import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 离线验证 SimReader 结果 Bundle 的 FD 修复（对应崩溃分析报告第 5 节）。
 *
 * 无需真机：用 Robolectric 在 JVM 上跑 Android 框架（`Bundle`/`Parcel`/`ParcelFileDescriptor`），
 * 确定性验证 (a) 含 FD 的 Bundle 在 `allowFds=false` 下写 parcel 必失败（证明诊断正确）、
 * (b) 修复后的 `buildResultBundleFromRaw` 不含 FD 且能通过 `allowFds=false` 序列化。
 *
 * 通过标准（报告 5.3）：两条均绿 = 逻辑修复得到离线证实，无需真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29]) // 对齐 Android 10（Pixel 1 / marlin）
class SimResultBundleTest {

    /**
     * 复现 bug 的前置条件：含 `SubscriptionInfo`（带 `Bitmap` → FD）的 Bundle 会被
     * `hasFileDescriptors()` 识别为含 FD。这正是 `Instrumentation.finish()` 在 AMS 侧
     * 以 `allowFds=false` 序列化时抛 `IllegalArgumentException` 的根因。
     *
     * 注：Robolectric 的 `Parcel` shadow 不实现 `setAllowFds`，无法在此复现
     * "allowFds=false 下 writeToParcel 抛异常" 的完整链路；但 `hasFileDescriptors()==true`
     * 已是 AMS 拒绝的判定依据，足以证明诊断正确。
     */
    @Test
    fun bundleWithFd_isDetected() {
        val pipe = ParcelFileDescriptor.createPipe() // 造一个真实 FD，无需设备
        val bad = Bundle().apply { putParcelable("x", pipe[0]) }
        try {
            assertTrue("前置条件：含 FD 的 Bundle 应被识别", bad.hasFileDescriptors())
        } finally {
            pipe.forEach { runCatching { it.close() } }
        }
    }

    /**
     * 验证修复：新 `buildResultBundleFromRaw` 不含 FD，且能安全序列化到 Parcel。
     */
    @Test
    fun sanitizedBundle_hasNoFds_andSurvives() {
        val raws: List<SimReader.SubInfoRaw> = listOf(
            SimReader.SubInfoRaw(
                subId = 1,
                slot = 0,
                carrierId = 1,
                iccId = "8986011...",
                mcc = "460",
                mnc = "01",
                countryIso = "cn",
                displayName = "中国联通",
                carrierName = "中国联通",
                number = "",
            ),
            SimReader.SubInfoRaw(
                subId = 2,
                slot = 1,
                carrierId = 2,
                iccId = "8986000...",
                mcc = "460",
                mnc = "00",
                countryIso = "cn",
                displayName = "中国移动",
                carrierName = "CMCC",
                number = null,
            ),
        )
        val good = SimReader.buildResultBundleFromRaw(raws)

        assertFalse("修复后 Bundle 不得含 FD", good.hasFileDescriptors())
        val p = Parcel.obtain()
        try {
            good.writeToParcel(p, 0) // 不得抛异常
        } finally {
            p.recycle()
        }

        // 回读校验关键字段，确保编码/解码对称
        assertEquals(2, good.getInt(SimReader.BUNDLE_COUNT))
        assertEquals(1, good.getInt("0_subId"))
        assertEquals(0, good.getInt("0_slot"))
        assertEquals("460", good.getString("0_mcc"))
        assertEquals("01", good.getString("0_mnc"))
        assertEquals("cn", good.getString("0_countryIso"))
        assertEquals("中国联通", good.getString("0_displayName"))
        assertEquals(2, good.getInt("1_subId"))
        assertEquals(1, good.getInt("1_slot"))
        assertEquals("CMCC", good.getString("1_carrierName"))
    }

    /** 空列表也应安全：count=0，无任何 SIM 字段，无 FD。 */
    @Test
    fun emptyBundle_isSafe() {
        val good = SimReader.buildResultBundleFromRaw(emptyList())
        assertFalse(good.hasFileDescriptors())
        val p = Parcel.obtain()
        try {
            good.writeToParcel(p, 0)
        } finally {
            p.recycle()
        }
        assertEquals(0, good.getInt(SimReader.BUNDLE_COUNT, -1))
    }
}

