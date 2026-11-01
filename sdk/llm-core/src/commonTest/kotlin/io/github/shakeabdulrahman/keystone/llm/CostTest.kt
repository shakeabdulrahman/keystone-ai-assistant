package io.github.shakeabdulrahman.keystone.llm

import io.github.shakeabdulrahman.keystone.llm.testing.FakeProvider
import io.github.shakeabdulrahman.keystone.llm.testing.answer
import io.github.shakeabdulrahman.keystone.llm.testing.testClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CostTest {
    private val model = ModelRef("a", "m")

    // Illustrative numbers for tests only; not real prices.
    private val price = ModelPrice(
        inputPerMillion = Money.parse("0.15", "USD"),
        outputPerMillion = Money.parse("0.60", "USD"),
        cachedInputPerMillion = Money.parse("0.075", "USD"),
    )

    @Test
    fun moneyParsesAndFormatsExactly() {
        assertEquals(150_000_000L, Money.parse("0.15", "USD").nanos)
        assertEquals(-2_500_000_000L, Money.parse("-2.5", "USD").nanos)
        assertEquals("0.150000", Money.parse("0.15", "USD").toDecimalString())
        assertEquals("0.15 USD", Money.parse("0.150", "USD").toString())
        assertFailsWith<IllegalArgumentException> { Money.parse("1e-3", "USD") }
        assertFailsWith<IllegalArgumentException> { Money.parse("0.0000000001", "USD") }
    }

    @Test
    fun formattingRoundsHalfEven() {
        assertEquals("0.12", Money(125_000_000, "USD").toDecimalString(2)) // tie -> even
        assertEquals("0.14", Money(135_000_000, "USD").toDecimalString(2)) // tie -> even
        assertEquals("0.13", Money(125_000_001, "USD").toDecimalString(2))
        assertEquals("-0.12", Money(-125_000_000, "USD").toDecimalString(2))
        assertEquals("2", Money(1_500_000_000, "USD").toDecimalString(0))
    }

    @Test
    fun costUsesReportedUsageAndCachedPrice() {
        val usage = Usage(inputTokens = 1_000_000, outputTokens = 2_000, cachedInputTokens = 400_000)
        val report = assertIs<CostReport.Priced>(CostReport.calculate(usage, price, "2026-10"))
        // 600k × 0.15/M + 400k × 0.075/M = 0.09 + 0.03 = 0.12
        assertEquals(Money.parse("0.12", "USD"), report.input)
        // 2k × 0.60/M = 0.0012
        assertEquals(Money.parse("0.0012", "USD"), report.output)
        assertEquals(Money.parse("0.1212", "USD"), report.total)
        assertEquals(false, report.estimated)
        assertEquals("2026-10", report.priceTableVersion)
    }

    @Test
    fun subNanoResultsRoundHalfEven() {
        // 0.0015 USD per million = 1.5 nano-USD per token: 1 token -> 1.5 -> 2 (half-even); 3 tokens -> 4.5 -> 4.
        val tiny = ModelPrice(Money.parse("0.0015", "USD"), Money.parse("0", "USD"))
        assertEquals(2L, (CostReport.calculate(Usage(1, 0), tiny, "v") as CostReport.Priced).input.nanos)
        assertEquals(4L, (CostReport.calculate(Usage(3, 0), tiny, "v") as CostReport.Priced).input.nanos)
    }

    @Test
    fun largeTokenCountsDoNotOverflow() {
        val expensive = ModelPrice(Money.parse("150", "USD"), Money.parse("600", "USD"))
        val report = CostReport.calculate(Usage(5_000_000_000, 1), expensive, "v") as CostReport.Priced
        assertEquals(Money.parse("750000", "USD"), report.input)
    }

    @Test
    fun missingPriceOrUsageIsUnavailableNotZero() {
        assertEquals(
            CostReport.Unavailable(CostReport.Unavailable.Reason.NoPriceForModel, "v"),
            CostReport.calculate(Usage(1, 1), null, "v"),
        )
        assertEquals(
            CostReport.Unavailable(CostReport.Unavailable.Reason.UsageUnknown, "v"),
            CostReport.calculate(Usage.Unknown, price, "v"),
        )
        assertEquals(
            CostReport.Unavailable(CostReport.Unavailable.Reason.UsageUnknown, "v"),
            CostReport.calculate(Usage(inputTokens = 10), price, "v"), // partial usage
        )
    }

    @Test
    fun mixedCurrenciesAreRejected() {
        assertFailsWith<IllegalArgumentException> { Money.parse("1", "USD") + Money.parse("1", "EUR") }
        assertFailsWith<IllegalArgumentException> { ModelPrice(Money.parse("1", "USD"), Money.parse("1", "EUR")) }
    }

    @Test
    fun clientAttachesCostFromItsPriceTable() = runTest {
        val provider = FakeProvider("a") { _, _ -> answer("x", usage = Usage(1_000_000, 1_000_000)) }
        val (client, _) = testClient(provider) { pricing = PriceTable("t1", mapOf(model to price)) }
        val cost = assertIs<CostReport.Priced>(client.chat(ChatRequest.of(model, Message.user("q"))).cost)
        assertEquals(Money.parse("0.75", "USD"), cost.total)
    }

    @Test
    fun withoutAPriceTableThereIsNoCost() = runTest {
        val (client, _) = testClient(FakeProvider("a") { _, _ -> answer("x") })
        assertNull(client.chat(ChatRequest.of(model, Message.user("q"))).cost)
    }

    @Test
    fun missingUsageStaysUnknownUnlessAnEstimatorIsConfigured() = runTest {
        val provider = FakeProvider("a") { _, _ -> answer("12345678", usage = Usage.Unknown) }

        val (plain, _) = testClient(provider)
        val unknown = plain.chat(ChatRequest.of(model, Message.user("abcd"))).usage
        assertEquals(Usage.Unknown, unknown)
        assertEquals(UsageSource.Unknown, unknown.source)

        val (estimating, _) = testClient(provider) {
            tokenEstimator = TokenEstimator.charsPerToken(4.0)
            pricing = PriceTable("t1", mapOf(model to price))
        }
        val response = estimating.chat(ChatRequest.of(model, Message.user("abcd")))
        assertEquals(UsageSource.Estimated, response.usage.source)
        assertEquals(2L, response.usage.outputTokens) // 8 chars / 4
        assertEquals(true, (response.cost as CostReport.Priced).estimated)
    }
}
