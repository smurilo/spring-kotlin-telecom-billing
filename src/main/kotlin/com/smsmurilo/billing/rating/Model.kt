package com.smsmurilo.billing.rating

import java.math.BigDecimal
import java.time.LocalDateTime

enum class UsageType { VOICE, SMS, DATA }

enum class Plan { PRE_PAGO, CONTROLE, POS_PAGO }

/** Raw Call Detail Record as it arrives from the network mediation file. */
data class Cdr(
    val msisdn: String,
    val type: UsageType,
    val startedAt: LocalDateTime,
    /** seconds for VOICE, messages for SMS, kilobytes for DATA */
    val units: Long,
    val destination: String?,
)

data class RatedUsage(
    val msisdn: String,
    val type: UsageType,
    val startedAt: LocalDateTime,
    val units: Long,
    val billedUnits: Long,
    val amount: BigDecimal,
)

class InvalidCdrException(message: String) : RuntimeException(message)
