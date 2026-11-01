package io.github.shakeabdulrahman.keystone.llm

/**
 * An exact amount of money in billionths (nanos) of a [currency] unit. No floating point:
 * prices and costs add up exactly, and rounding only happens when you format.
 */
public data class Money(val nanos: Long, val currency: String) : Comparable<Money> {
    init {
        require(currency.isNotBlank()) { "currency is required" }
    }

    public operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Can't add $currency and ${other.currency}" }
        return Money(addExact(nanos, other.nanos), currency)
    }

    override fun compareTo(other: Money): Int {
        require(currency == other.currency) { "Can't compare $currency and ${other.currency}" }
        return nanos.compareTo(other.nanos)
    }

    /** Formats with [scale] decimals, rounding half-even. `Money.parse("0.0000125","USD").toDecimalString(5)` = "0.00001". */
    public fun toDecimalString(scale: Int = 6): String {
        require(scale in 0..9) { "scale must be 0..9" }
        val divisor = POW10[9 - scale]
        val rounded = roundHalfEven(nanos, divisor) // in units of 10^-scale
        val negative = rounded < 0
        val abs = if (negative) -rounded else rounded
        val unit = POW10[scale]
        val whole = abs / unit
        val frac = abs % unit
        val sign = if (negative) "-" else ""
        return if (scale == 0) "$sign$whole" else "$sign$whole.${frac.toString().padStart(scale, '0')}"
    }

    override fun toString(): String = "${toDecimalString(9).trimEnd('0').trimEnd('.')} $currency"

    public companion object {
        /** Parses a plain decimal string such as `"0.15"` or `"2.5"`; at most 9 decimals. */
        public fun parse(amount: String, currency: String): Money {
            val s = amount.trim()
            require(DECIMAL.matches(s)) { "Not a plain decimal amount: '$amount'" }
            val negative = s.startsWith("-")
            val body = s.removePrefix("-")
            val whole = body.substringBefore('.')
            val frac = body.substringAfter('.', "")
            require(frac.length <= 9) { "At most 9 decimal places: '$amount'" }
            val nanos = addExact(
                multiplyExact(whole.toLong(), 1_000_000_000L),
                frac.padEnd(9, '0').toLong(),
            )
            return Money(if (negative) -nanos else nanos, currency)
        }

        public fun zero(currency: String): Money = Money(0, currency)

        private val DECIMAL = Regex("^-?\\d+(\\.\\d+)?$")
    }
}

/**
 * Prices of one model, per million tokens.
 * @property cachedInputPerMillion price of cached input tokens; `null` = same as input.
 */
public data class ModelPrice(
    val inputPerMillion: Money,
    val outputPerMillion: Money,
    val cachedInputPerMillion: Money? = null,
) {
    init {
        require(inputPerMillion.currency == outputPerMillion.currency) { "Mixed currencies" }
        require(cachedInputPerMillion == null || cachedInputPerMillion.currency == inputPerMillion.currency) { "Mixed currencies" }
    }

    public val currency: String get() = inputPerMillion.currency
}

/**
 * Your prices. The SDK ships none: vendor prices change, differ by account and region, and
 * an SDK can't keep them correct. Load them from your own config and give the table a
 * [version] (a date or tag) so every [CostReport] says which prices it used.
 */
public class PriceTable(
    public val version: String,
    prices: Map<ModelRef, ModelPrice>,
) {
    private val prices = prices.toMap()

    public fun priceOf(model: ModelRef): ModelPrice? = prices[model]

    override fun toString(): String = "PriceTable($version, ${prices.size} models)"
}

/** The cost of one response, or why it can't be computed. */
public sealed interface CostReport {
    public data class Priced(
        val input: Money,
        val output: Money,
        val total: Money,
        /** True when the usage it's based on was estimated, not reported. */
        val estimated: Boolean,
        val priceTableVersion: String,
    ) : CostReport

    public data class Unavailable(val reason: Reason, val priceTableVersion: String) : CostReport {
        public enum class Reason { NoPriceForModel, UsageUnknown }
    }

    public companion object {
        /**
         * Cost of [usage] at [price]: tokens × price ÷ 1,000,000, rounded half-even to the nano.
         * Needs both input and output counts; a partial count gives [Unavailable].
         */
        public fun calculate(usage: Usage, price: ModelPrice?, priceTableVersion: String): CostReport {
            if (price == null) return Unavailable(Unavailable.Reason.NoPriceForModel, priceTableVersion)
            val inTokens = usage.inputTokens
            val outTokens = usage.outputTokens
            if (inTokens == null || outTokens == null || usage.source == UsageSource.Unknown) {
                return Unavailable(Unavailable.Reason.UsageUnknown, priceTableVersion)
            }
            val cached = (usage.cachedInputTokens ?: 0L).coerceIn(0L, inTokens)
            val cachedPrice = price.cachedInputPerMillion ?: price.inputPerMillion
            val input = Money(
                addExact(
                    perMillion(inTokens - cached, price.inputPerMillion.nanos),
                    perMillion(cached, cachedPrice.nanos),
                ),
                price.currency,
            )
            val output = Money(perMillion(outTokens, price.outputPerMillion.nanos), price.currency)
            return Priced(input, output, input + output, usage.source == UsageSource.Estimated, priceTableVersion)
        }

        /** tokens × nanosPerMillion / 1e6 without overflow for realistic inputs, rounded half-even. */
        private fun perMillion(tokens: Long, nanosPerMillion: Long): Long {
            require(tokens >= 0) { "negative token count" }
            val millions = tokens / 1_000_000
            val rest = tokens % 1_000_000
            return addExact(
                multiplyExact(millions, nanosPerMillion),
                roundHalfEven(multiplyExact(rest, nanosPerMillion), 1_000_000),
            )
        }
    }
}

/** Approximates token counts when a provider reports none. Results are labelled [UsageSource.Estimated]. */
public fun interface TokenEstimator {
    public fun estimate(text: String): Long

    public companion object {
        /**
         * A crude, tokenizer-free heuristic: ceil(characters / [charsPerToken]). Fine for
         * rough budgeting of English text; wrong for code and many languages.
         */
        public fun charsPerToken(charsPerToken: Double = 4.0): TokenEstimator {
            require(charsPerToken > 0) { "charsPerToken must be positive" }
            return TokenEstimator { text -> kotlin.math.ceil(text.length / charsPerToken).toLong() }
        }
    }
}

private val POW10 = LongArray(10).also { a -> a[0] = 1; for (i in 1 until 10) a[i] = a[i - 1] * 10 }

/** value / divisor, rounded half-even. divisor > 0. */
internal fun roundHalfEven(value: Long, divisor: Long): Long {
    var q = value / divisor
    val r = value % divisor
    val twice = kotlin.math.abs(r) * 2
    if (twice > divisor || (twice == divisor && q % 2 != 0L)) q += if (value < 0) -1 else 1
    return q
}

internal fun addExact(a: Long, b: Long): Long {
    val r = a + b
    if (((a xor r) and (b xor r)) < 0) throw ArithmeticException("long overflow")
    return r
}

internal fun multiplyExact(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    val r = a * b
    if (r / b != a || (a == -1L && b == Long.MIN_VALUE) || (b == -1L && a == Long.MIN_VALUE)) {
        throw ArithmeticException("long overflow")
    }
    return r
}
