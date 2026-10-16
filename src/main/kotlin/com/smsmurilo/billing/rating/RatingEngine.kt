package com.smsmurilo.billing.rating

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek

/** One pricing rule per usage type (Strategy pattern); adding 5G slicing or roaming = adding a strategy. */
fun interface RatingStrategy {
    fun rate(cdr: Cdr, plan: Plan): Pair<Long, BigDecimal>
}

private fun money(value: String) = BigDecimal(value)

internal val voiceStrategy = RatingStrategy { cdr, plan ->
    // Telecom rule: first minute is charged in full, then 6-second increments.
    val billedSeconds = if (cdr.units <= 60) 60 else 60 + ((cdr.units - 60 + 5) / 6) * 6
    val perMinute = when (plan) {
        Plan.PRE_PAGO -> money("1.49")
        Plan.CONTROLE -> money("0.99")
        Plan.POS_PAGO -> money("0.59")
    }
    val offPeak = cdr.startedAt.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) || cdr.startedAt.hour !in 8..19
    val rate = if (offPeak) perMinute.multiply(money("0.5")) else perMinute
    billedSeconds to rate.multiply(BigDecimal(billedSeconds)).divide(BigDecimal(60), 2, RoundingMode.HALF_UP)
}

internal val smsStrategy = RatingStrategy { cdr, plan ->
    val unit = if (plan == Plan.POS_PAGO) money("0.10") else money("0.25")
    cdr.units to unit.multiply(BigDecimal(cdr.units)).setScale(2)
}

internal val dataStrategy = RatingStrategy { cdr, plan ->
    // Data is rounded up to the next 100 KB block; post-paid has the bundle, so it's not charged per use.
    val blocks = (cdr.units + 99) / 100
    val perBlock = when (plan) {
        Plan.PRE_PAGO -> money("0.05")
        Plan.CONTROLE -> money("0.02")
        Plan.POS_PAGO -> BigDecimal.ZERO
    }
    blocks * 100 to perBlock.multiply(BigDecimal(blocks)).setScale(2)
}

@Component
class RatingEngine {

    private val strategies = mapOf(
        UsageType.VOICE to voiceStrategy,
        UsageType.SMS to smsStrategy,
        UsageType.DATA to dataStrategy,
    )

    fun rate(cdr: Cdr, plan: Plan): RatedUsage {
        if (cdr.units <= 0) throw InvalidCdrException("CDR com unidades inválidas para ${cdr.msisdn}")
        val (billed, amount) = strategies.getValue(cdr.type).rate(cdr, plan)
        return RatedUsage(cdr.msisdn, cdr.type, cdr.startedAt, cdr.units, billed, amount)
    }
}
