package com.smsmurilo.billing.batch

import com.smsmurilo.billing.rating.InvalidCdrException
import com.smsmurilo.billing.rating.Plan
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** Small read-through cache: millions of CDRs hit a few thousand subscribers. */
@Component
class SubscriberDirectory(private val jdbc: JdbcTemplate) {

    private val cache = ConcurrentHashMap<String, Plan>()

    fun planOf(msisdn: String): Plan = cache.getOrPut(msisdn) {
        jdbc.query("select plan from subscribers where msisdn = ?", { rs, _ -> Plan.valueOf(rs.getString(1)) }, msisdn)
            .firstOrNull() ?: throw InvalidCdrException("Assinante $msisdn não encontrado")
    }

    fun evict() = cache.clear()
}
