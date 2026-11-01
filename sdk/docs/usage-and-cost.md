# Usage and cost

## Usage

`ChatResponse.usage` is `Usage(inputTokens, outputTokens, cachedInputTokens, source)`.

- `source = Reported`: counted by the provider (OpenAI `usage`, Groq `x_groq.usage`, Gemini
  `usageMetadata`; Gemini's thinking tokens are included in output).
- `source = Unknown`: the provider didn't say. Counts are `null`, never a guessed number.
- `source = Estimated`: only if you set `tokenEstimator`. Missing counts are filled in
  locally and the whole usage is labelled estimated.

```kotlin
LlmClient { tokenEstimator = TokenEstimator.charsPerToken(4.0) } // crude; or plug in a real tokenizer
```

No tokenizer ships with the SDK: they're model-specific and large. Usage is for the attempt
that answered. Failed attempts may also have been billed, but providers rarely report usage
for them.

For OpenAI-compatible servers, streamed usage needs `stream_options.include_usage`
(`OpenAiCompatibleOptions.includeStreamUsage`, on by default).

## Cost

The SDK ships **no prices**. Vendor prices change and vary by account, so supply your own,
versioned:

```kotlin
LlmClient {
    pricing = PriceTable(
        version = "2026-10-29",   // where these numbers came from, and when
        prices = mapOf(
            ModelRef("groq", groqModel) to ModelPrice(
                inputPerMillion = Money.parse(myConfig.input, "USD"),
                outputPerMillion = Money.parse(myConfig.output, "USD"),
                cachedInputPerMillion = null,  // null = same as input
            ),
        ),
    )
}
when (val cost = response.cost) {
    is CostReport.Priced -> show(cost.total.toDecimalString(6), cost.estimated, cost.priceTableVersion)
    is CostReport.Unavailable -> show("cost unknown: ${cost.reason}")
    null -> Unit // no price table configured
}
```

- Money is exact: `Long` nano-units of a currency, parsed from decimal strings. There's no
  floating point.
- `cost = tokens × pricePerMillion ÷ 1,000,000`, rounded half-even to the nano. Cached input
  tokens use `cachedInputPerMillion` when set.
- A missing price gives `Unavailable(NoPriceForModel)`, and missing or partial usage gives
  `Unavailable(UsageUnknown)`. Neither becomes zero.
- Costs based on estimated usage have `estimated = true`.
- Currencies can't be mixed.
