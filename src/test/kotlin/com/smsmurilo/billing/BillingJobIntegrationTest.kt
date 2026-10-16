package com.smsmurilo.billing

import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.test.JobLauncherTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import kotlin.test.assertEquals

@SpringBootTest
@SpringBatchTest
class BillingJobIntegrationTest {

    @Autowired lateinit var jobLauncher: JobLauncherTestUtils
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `rates valid cdrs, skips bad ones and builds invoices`() {
        val file = ClassPathResource("sample/cdr-sample.csv").file.absolutePath
        val execution = jobLauncher.launchJob(JobParametersBuilder().addString("file", file).toJobParameters())

        assertEquals(BatchStatus.COMPLETED, execution.status)
        val rating = execution.stepExecutions.first { it.stepName == "rateUsage" }
        assertEquals(5, rating.writeCount)
        assertEquals(3, rating.skipCount) // zero units, unknown subscriber, corrupted line

        val total = jdbc.queryForObject("select total from invoices where msisdn = '5582999990001'", BigDecimal::class.java)
        assertEquals(BigDecimal("90.55"), total) // 89.90 fee + 0.65 voice + data in bundle
    }
}
