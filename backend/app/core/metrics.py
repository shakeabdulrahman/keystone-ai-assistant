"""Prometheus metrics: the numbers that tell you whether an AI app is healthy.

Beyond the usual request rate / errors / latency, an AI gateway needs:
- time to first token (what users feel as "speed"), and full answer duration;
- token usage (cost, even with free models: capacity);
- tool calls and failures per tool (an HR system outage shows up here first);
- retrieval: how many passages were found and how many the answer actually cited;
- actions by outcome (a spike in "expired" means people don't see the cards);
- rate-limit rejections and push delivery.

Labels are low-cardinality on purpose (route templates, tool names, statuses), never
user IDs or text: those belong in logs, and would blow up the metrics database.
"""

from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

_LATENCY_BUCKETS = (0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)
_LLM_BUCKETS = (0.25, 0.5, 1, 2, 4, 8, 15, 30, 60, 120)

HTTP_REQUESTS = Counter(
    "keystone_http_requests_total", "HTTP requests", ["method", "route", "status"]
)
HTTP_LATENCY = Histogram(
    "keystone_http_request_duration_seconds",
    "Time to the end of the response (streams: the whole stream)",
    ["route"],
    buckets=(*_LATENCY_BUCKETS, 30, 60, 120),
)
CHAT_TURNS = Counter(
    "keystone_chat_turns_total", "Chat answers by outcome", ["status", "input_mode"]
)
CHAT_TTFT = Histogram(
    "keystone_chat_time_to_first_output_seconds",
    "From request to the first text or tool activity",
    buckets=_LLM_BUCKETS,
)
CHAT_DURATION = Histogram(
    "keystone_chat_duration_seconds", "Whole answer, including tools", buckets=_LLM_BUCKETS
)
AGENT_STEPS = Histogram(
    "keystone_agent_steps", "Model calls per answer", buckets=(1, 2, 3, 4, 5, 6)
)
LLM_TOKENS = Counter("keystone_llm_tokens_total", "Tokens used", ["direction"])
TOOL_CALLS = Counter("keystone_tool_calls_total", "Tool calls", ["tool", "status"])
TOOL_LATENCY = Histogram(
    "keystone_tool_latency_seconds", "Tool execution time", ["tool"], buckets=_LATENCY_BUCKETS
)
RAG_SOURCES = Counter("keystone_rag_sources_total", "Policy passages retrieved and cited", ["kind"])
ACTIONS = Counter("keystone_actions_total", "Proposed actions by outcome", ["tool", "status"])
RATE_LIMITED = Counter("keystone_rate_limited_total", "Requests rejected by rule", ["rule"])
PUSHES = Counter("keystone_push_total", "Push notifications", ["outcome"])


def render() -> tuple[bytes, str]:
    return generate_latest(), CONTENT_TYPE_LATEST
