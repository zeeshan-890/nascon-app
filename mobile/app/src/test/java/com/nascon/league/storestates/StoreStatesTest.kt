package com.nascon.league.storestates

import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ApiError
import com.nascon.league.ui.store.BannerKind
import com.nascon.league.ui.store.resolveFailure
import com.nascon.league.ui.store.payEnabled
import com.nascon.league.ui.store.payHint
import com.nascon.league.ui.store.PayHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoreStatesTest {
    @Test fun errorMapperWordingForPaymentStatesIsPlain() {
        for (code in listOf("PAYMENT_DECLINED", "PAYMENT_CAPTURE_FAILED", "OUT_OF_STOCK")) {
            val m = ErrorMapper.forCode(code)
            assertTrue(code, m.isNotBlank())
            assertFalse(code, m.contains("_"))
        }
    }

    @Test fun declinedAndCaptureMapToFullStateBanners() {
        val declined = resolveFailure(ApiError.Http(402, "PAYMENT_DECLINED", "x", null), hasDiscount = false)
        val capture = resolveFailure(ApiError.Http(402, "PAYMENT_CAPTURE_FAILED", "x", null), hasDiscount = false)
        assertEquals(BannerKind.Declined, declined.banner)
        assertEquals(BannerKind.CaptureFailed, capture.banner)
    }

    @Test fun offlineDisablesPayAndShowsOfflineHint() {
        assertEquals(PayHint.Offline, payHint(online = false, lineCount = 1, hasBlockingProblem = false))
        assertFalse(payEnabled(online = false, inFlight = false, lineCount = 1, hasBlockingProblem = false))
        assertFalse(payEnabled(online = true, inFlight = true, lineCount = 1, hasBlockingProblem = false))
    }

    @Test fun stringsFileHasAllKeys() {
        val f = listOf(
            File("src/main/res/values/strings_storestates.xml"),
            File("app/src/main/res/values/strings_storestates.xml"),
        ).first { it.exists() }
        val xml = f.readText()
        for (k in listOf(
            "store_empty_title", "cart_empty_title", "go_to_checkout", "paying_title", "declined_title",
            "capture_title", "offline_strip", "pay_needs_internet", "receipt_saved", "remove_sold_out",
        )) assertTrue(k, xml.contains("name=\"storestates_$k\""))
    }
}
