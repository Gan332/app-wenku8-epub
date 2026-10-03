package com.example.hyperreader

import com.example.hyperreader.auth.CfChallengeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloudflare 验证通过判定（0.18.0）的夹具。
 *
 * 关键点：拿到 `cf_clearance` 就算通过；否则看标题是否已不是挑战页。
 * 这些判定只用于**关闭用户已完成的验证窗口**，不用于任何绕过（AGENTS §4.2）。
 */
class CfChallengeStateTest {

    @Test
    fun clearanceCookieMeansPassed() {
        assertTrue(CfChallengeState.isSatisfied("Just a moment...", hasClearance = true))
        assertTrue(CfChallengeState.isSatisfied(null, hasClearance = true))
        assertTrue(CfChallengeState.isSatisfied("", hasClearance = true))
    }

    @Test
    fun challengeTitlesMeanStillVerifying() {
        assertFalse(CfChallengeState.isSatisfied("Just a moment...", hasClearance = false))
        assertFalse(CfChallengeState.isSatisfied("Attention Required! | Cloudflare", hasClearance = false))
        assertFalse(CfChallengeState.isSatisfied("Checking your browser before accessing", false))
    }

    @Test
    fun plainTitleWithoutClearanceCountsAsSoftPass() {
        // 某些软挑战不发 clearance，仅页面正常返回——标题已正常即视为通过
        assertTrue(CfChallengeState.isSatisfied("轻小说文库 - 书籍详情", hasClearance = false))
        assertTrue(CfChallengeState.isSatisfied("Wenku8", hasClearance = false))
    }

    @Test
    fun emptyTitleDoesNotCountAsPass() {
        // 空标题往往是“刚跳转、还没渲染”，不能当作通过
        assertFalse(CfChallengeState.isSatisfied(null, hasClearance = false))
        assertFalse(CfChallengeState.isSatisfied("", hasClearance = false))
        assertFalse(CfChallengeState.isSatisfied("   ", hasClearance = false))
    }
}