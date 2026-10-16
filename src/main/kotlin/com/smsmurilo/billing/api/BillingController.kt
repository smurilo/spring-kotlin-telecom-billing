package com.smsmurilo.billing.api

import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

@RestController
@RequestMapping("/billing")
class BillingController(
    private val launcher: JobLauncher,
    private val billingJob: Job,
    private val jdbc: JdbcTemplate,
) {
    data class RunResponse(val executionId: Long, val status: String, val skipped: Long, val rated: Long)
    data class Invoice(val msisdn: String, val voice: BigDecimal, val sms: BigDecimal, val data: BigDecimal, val total: BigDecimal)

    @PostMapping("/runs")
    fun run(@RequestParam("file") file: String): RunResponse {
        val params = JobParametersBuilder()
            .addString("file", file)
            .addLong("requestedAt", System.currentTimeMillis())
            .toJobParameters()
        val execution = launcher.run(billingJob, params)
        val rating = execution.stepExecutions.first { it.stepName == "rateUsage" }
        return RunResponse(execution.id, execution.status.name, rating.skipCount, rating.writeCount)
    }

    @GetMapping("/invoices/{msisdn}")
    fun invoice(@PathVariable("msisdn") msisdn: String): Invoice? =
        jdbc.query(
            "select msisdn, voice_amount, sms_amount, data_amount, total from invoices where msisdn = ?",
            { rs, _ -> Invoice(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)) },
            msisdn,
        ).firstOrNull()
}
