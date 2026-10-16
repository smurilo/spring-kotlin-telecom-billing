package com.smsmurilo.billing.batch

import com.smsmurilo.billing.rating.*
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.SkipListener
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.database.JdbcBatchItemWriter
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder
import org.springframework.batch.item.file.FlatFileItemReader
import org.springframework.batch.item.file.FlatFileParseException
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.FileSystemResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDateTime
import javax.sql.DataSource

@Configuration
class BillingJobConfig(
    private val jobRepository: JobRepository,
    private val txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun billingJob(rateUsageStep: Step, buildInvoicesStep: Step): Job =
        JobBuilder("billingJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .start(rateUsageStep)
            .next(buildInvoicesStep)
            .build()

    @Bean
    @StepScope
    fun cdrReader(@Value("#{jobParameters['file']}") file: String): FlatFileItemReader<Cdr> =
        FlatFileItemReaderBuilder<Cdr>()
            .name("cdrReader")
            .resource(FileSystemResource(file))
            .linesToSkip(1)
            .delimited().names("msisdn", "type", "startedAt", "units", "destination")
            .fieldSetMapper { fs ->
                Cdr(
                    msisdn = fs.readString("msisdn"),
                    type = UsageType.valueOf(fs.readString("type")),
                    startedAt = LocalDateTime.parse(fs.readString("startedAt")),
                    units = fs.readLong("units"),
                    destination = fs.readString("destination").ifBlank { null },
                )
            }
            .build()

    @Bean
    fun ratingProcessor(engine: RatingEngine, directory: SubscriberDirectory) =
        ItemProcessor<Cdr, RatedUsage> { cdr -> engine.rate(cdr, directory.planOf(cdr.msisdn)) }

    @Bean
    fun ratedUsageWriter(dataSource: DataSource): JdbcBatchItemWriter<RatedUsage> =
        JdbcBatchItemWriterBuilder<RatedUsage>()
            .dataSource(dataSource)
            .sql(
                """insert into rated_usage (msisdn, usage_type, started_at, units, billed_units, amount)
                   values (?, ?, ?, ?, ?, ?)"""
            )
            .itemPreparedStatementSetter { u, ps ->
                ps.setString(1, u.msisdn); ps.setString(2, u.type.name)
                ps.setObject(3, u.startedAt); ps.setLong(4, u.units)
                ps.setLong(5, u.billedUnits); ps.setBigDecimal(6, u.amount)
            }
            .build()

    @Bean
    fun rateUsageStep(
        cdrReader: FlatFileItemReader<Cdr>,
        ratingProcessor: ItemProcessor<Cdr, RatedUsage>,
        ratedUsageWriter: JdbcBatchItemWriter<RatedUsage>,
        @Value("\${billing.chunk-size:500}") chunkSize: Int,
    ): Step = StepBuilder("rateUsage", jobRepository)
        .chunk<Cdr, RatedUsage>(chunkSize, txManager)
        .reader(cdrReader)
        .processor(ratingProcessor)
        .writer(ratedUsageWriter)
        // Bad records from mediation must not stop the billing cycle: skip, log and keep going.
        .faultTolerant()
        .skip(FlatFileParseException::class.java)
        .skip(InvalidCdrException::class.java)
        .skip(IllegalArgumentException::class.java)
        .skipLimit(1_000)
        .listener(object : SkipListener<Cdr, RatedUsage> {
            override fun onSkipInRead(t: Throwable) = log.warn("Linha ignorada na leitura: {}", t.message)
            override fun onSkipInProcess(item: Cdr, t: Throwable) = log.warn("CDR ignorado {}: {}", item, t.message)
        })
        .build()

    @Bean
    fun buildInvoicesStep(jdbc: JdbcTemplate, directory: SubscriberDirectory): Step =
        StepBuilder("buildInvoices", jobRepository)
            .tasklet({ _, chunk ->
                // Set-based aggregation in the database is far faster than doing it row by row in the JVM.
                jdbc.update("delete from invoices where cycle = ?", CYCLE)
                val created = jdbc.update(
                    """insert into invoices (msisdn, cycle, voice_amount, sms_amount, data_amount, total)
                       select r.msisdn, ?,
                              coalesce(sum(case when r.usage_type = 'VOICE' then r.amount end), 0),
                              coalesce(sum(case when r.usage_type = 'SMS' then r.amount end), 0),
                              coalesce(sum(case when r.usage_type = 'DATA' then r.amount end), 0),
                              sum(r.amount) + max(s.monthly_fee)
                         from rated_usage r join subscribers s on s.msisdn = r.msisdn
                        group by r.msisdn""", CYCLE
                )
                chunk.stepContext.stepExecution.writeCount = created.toLong()
                directory.evict()
                RepeatStatus.FINISHED
            }, txManager)
            .build()

    companion object {
        const val CYCLE = "CURRENT"
    }
}
