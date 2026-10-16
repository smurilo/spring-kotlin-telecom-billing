package com.smsmurilo.billing

import com.smsmurilo.billing.rating.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals

class RatingEngineTest {

    private val engine = RatingEngine()
    private val weekdayPeak = LocalDateTime.parse("2026-03-04T10:00:00")

    private fun cdr(type: UsageType, units: Long, at: LocalDateTime = weekdayPeak) =
        Cdr("5582999990001", type, at, units, null)

    @Test
    fun `voice charges first full minute then 6 second increments`() {
        val rated = engine.rate(cdr(UsageType.VOICE, 61), Plan.POS_PAGO)
        assertEquals(66, rated.billedUnits)
        assertEquals(BigDecimal("0.65"), rated.amount) // 66s * 0.59/60
    }

    @Test
    fun `off peak voice costs half`() {
        val night = engine.rate(cdr(UsageType.VOICE, 60, LocalDateTime.parse("2026-03-04T22:00:00")), Plan.PRE_PAGO)
        assertEquals(BigDecimal("0.75"), night.amount)
    }

    @Test
    fun `data rounds up to 100kb blocks and is free on post paid`() {
        assertEquals(BigDecimal("0.15"), engine.rate(cdr(UsageType.DATA, 250), Plan.PRE_PAGO).amount)
        assertEquals(BigDecimal("0.00"), engine.rate(cdr(UsageType.DATA, 250), Plan.POS_PAGO).amount)
    }

    @Test
    fun `rejects cdr without usage`() {
        assertThrows<InvalidCdrException> { engine.rate(cdr(UsageType.VOICE, 0), Plan.CONTROLE) }
    }
}
