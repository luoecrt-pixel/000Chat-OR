package com.example.openaichat

import org.json.JSONObject

data class OpenRouterClassifiedError(
    val userMessage: String,
    val retryEligible: Boolean,
    val retryAfterSeconds: Long?,
    val errorType: String?,
    val limitSource: String?,
    val resetsAt: String?,
    val providerName: String?,
    val providerCode: String?,
    val safeMetadataJson: String?
)

/**
 * Pure mapping of OpenRouter HTTP/SSE errors and rate-limit metadata to safe,
 * human-readable explanations without leaking raw payloads, flagged_input or API keys.
 */
object OpenRouterErrorClassifier {
    private val secretPattern = Regex(
        "(?i)(?:Bearer\\s+[A-Za-z0-9._-]+|\\b(?:sk-[A-Za-z0-9._-]{8,}|gh[pousr]_[A-Za-z0-9_]{12,}|github_pat_[A-Za-z0-9_]{12,})\\b)"
    )

    fun redactSecrets(text: String?): String? =
        text?.replace(secretPattern, "[REDACTED]")?.replace("\r", " ")?.replace("\n", " ")?.trim()

    fun parseRetryAfterSeconds(headerValue: String?): Long? =
        headerValue?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.coerceAtMost(86_400L)

    fun classify(
        error: JSONObject,
        stage: String,
        roundIndex: Int,
        httpStatus: Int? = null,
        retryAfterHeader: String? = null
    ): OpenRouterUpstreamException {
        val metadata = error.optJSONObject("metadata")
        fun scalar(o: JSONObject?, key: String): String? = when (val value = o?.opt(key)) {
            is String -> value.trim().takeIf { it.isNotEmpty() && it != "null" }
            is Number -> value.toString()
            else -> null
        }

        val raw = metadata?.opt("raw")
        val providerError = (if (raw is JSONObject) raw else if (raw is String && raw.length <= 32_000)
            try { JSONObject(raw) } catch (_: Exception) { null } else null)?.optJSONObject("error")

        val code = scalar(error, "code")
        val status = httpStatus ?: code?.toIntOrNull()?.takeIf { it in 400..599 }
        val metadataErrorType = scalar(metadata, "error_type") ?: scalar(providerError, "type")
        val errorType = metadataErrorType ?: scalar(error, "type") ?: code
        val limitSource = scalar(metadata, "limit_source") ?: scalar(error, "limit_source")
        val resetsAt = scalar(metadata, "resets_at") ?: scalar(error, "resets_at")
        val provider = scalar(metadata, "provider_name") ?: scalar(error, "provider_name")
        val providerCode = scalar(metadata, "provider_code") ?: scalar(error, "provider_code") ?: scalar(providerError, "code")
        val retryAfterSec = parseRetryAfterSeconds(retryAfterHeader)
            ?: scalar(metadata, "retry_after")?.toLongOrNull()?.takeIf { it > 0L }

        val rawMessage = redactSecrets(JsonProtocol.nonBlank(error, "message"))?.take(420) ?: "Unknown upstream error"
        val rawDetail = redactSecrets(JsonProtocol.nonBlank(providerError ?: JSONObject(), "message"))?.take(260)

        val friendlyReason = buildFriendlyReason(
            status = status,
            errorType = errorType,
            limitSource = limitSource,
            resetsAt = resetsAt,
            retryAfterSec = retryAfterSec,
            rawMessage = rawMessage,
            providerDetail = rawDetail,
            provider = provider
        )

        val retryEligible = when (status) {
            429, 502, 503, 504 -> true
            else -> false
        }

        val safeMeta = JSONObject().apply {
            provider?.let { put("provider_name", redactSecrets(it)?.take(120)) }
            providerCode?.let { put("provider_code", redactSecrets(it)?.take(120)) }
            metadataErrorType?.let { put("error_type", redactSecrets(it)?.take(100)) }
            limitSource?.let { put("limit_source", redactSecrets(it)?.take(100)) }
            resetsAt?.let { put("resets_at", redactSecrets(it)?.take(100)) }
            retryAfterSec?.let { put("retry_after_s", it) }
            put("retry_eligible", retryEligible)
            scalar(providerError, "param")?.let { put("upstream_param", redactSecrets(it)?.take(100)) }
            if (status == null || status < 500) {
                rawDetail?.let { put("upstream_message", it) }
            }
        }.takeIf { it.length() > 0 }?.toString()?.take(480)

        val detailSuffix = if (status != null && status >= 500) {
            ""
        } else {
            rawDetail?.takeIf { !rawMessage.contains(it, ignoreCase = true) }?.let { " · $it" }.orEmpty()
        }

        val fullMessage = buildString {
            append("OpenRouter ").append(stage).append(" error: ")
            if (friendlyReason != null) {
                append(friendlyReason).append(" (").append(rawMessage).append(detailSuffix).append(")")
            } else {
                append(rawMessage).append(detailSuffix)
            }
        }

        return OpenRouterUpstreamException(
            message = fullMessage,
            stage = stage,
            roundIndex = roundIndex,
            httpStatus = status,
            errorType = errorType,
            providerName = provider,
            providerCode = providerCode,
            safeMetadata = safeMeta,
            retryEligible = retryEligible,
            retryAfterSeconds = retryAfterSec,
            limitSource = limitSource,
            resetsAt = resetsAt
        )
    }

    private fun buildFriendlyReason(
        status: Int?,
        errorType: String?,
        limitSource: String?,
        resetsAt: String?,
        retryAfterSec: Long?,
        rawMessage: String,
        providerDetail: String?,
        provider: String?
    ): String? {
        val normalizedType = errorType?.lowercase().orEmpty()
        val lowerMsg = rawMessage.lowercase()
        val immutableThinking = (lowerMsg + " " + providerDetail.orEmpty().lowercase()).let {
            "thinking" in it && "blocks" in it && "cannot be modified" in it
        }
        val invalidToolSchema = (lowerMsg + " " + providerDetail.orEmpty().lowercase()).let {
            "input_schema" in it && "top level" in it &&
                listOf("oneof", "allof", "anyof").any { keyword -> keyword in it }
        }
        val retryHint = buildList {
            retryAfterSec?.let { add("Retry-After: ${it} с") }
            resetsAt?.let { add("сброс лимита: $it") }
        }.joinToString(", ").takeIf { it.isNotEmpty() }?.let { " [$it]" }.orEmpty()

        return when (status) {
            400 -> when {
                invalidToolSchema ->
                    "Несовместимая схема инструмента Claude. Требуется исправление приложения; автоматический повтор не выполнялся"
                immutableThinking ->
                    "Провайдер отклонил повторное использование подписанных блоков мышления Claude. " +
                        "Верните исходные модель и маршрут либо начните новый чат с нужным контекстом. " +
                        "История сохранена; автоматический повтор не выполнялся"
                normalizedType == "image_too_large" || "image_too_large" in lowerMsg ->
                    "Изображение превышает допустимый размер провайдера (можно включить совместимую копию JPEG или маршрут Прямой Anthropic)"
                normalizedType == "invalid_image" || "invalid_image" in lowerMsg ->
                    "Провайдер отклонил изображение как повреждённое или некорректное"
                normalizedType == "unsupported_image_format" || "unsupported_image_format" in lowerMsg ->
                    "Формат изображения не поддерживается выбранной моделью/маршрутом"
                normalizedType == "image_download_failed" || "image_download_failed" in lowerMsg ->
                    "Провайдер не смог загрузить изображение"
                normalizedType == "image_not_found" || "image_not_found" in lowerMsg ->
                    "Изображение не найдено на стороне провайдера"
                else -> null
            }
            402 -> {
                val sourceHint = when {
                    limitSource.equals("key", ignoreCase = true) || "key limit" in lowerMsg || "key quota" in lowerMsg ->
                        "достигнут лимит расходов API-ключа"
                    limitSource.equals("in_flight", ignoreCase = true) || "in-flight" in lowerMsg || "in_flight" in lowerMsg ->
                        "недостаточно свободного баланса из-за параллельных активных запросов (in-flight budget)"
                    else -> "недостаточно средств на балансе OpenRouter или исчерпан лимит"
                }
                "Оплата/лимит (402): $sourceHint$retryHint"
            }
            403 -> "Доступ ограничен фильтром безопасности / Guardrail (403)${provider?.let { " провайдера $it" }.orEmpty()}"
            429 -> {
                val src = limitSource?.let { " (источник: $it)" }.orEmpty()
                "Превышен лимит частоты запросов (429)$src$retryHint. Автоматический платный повтор не выполнялся"
            }
            503 -> "Нет доступного маршрута провайдера (503)${provider?.let { " для $it" }.orEmpty()}. Если выбран прямой Anthropic, провайдер может быть временно перегружен"
            in 500..599 -> "Временная внутренняя ошибка сервера провайдера ($status)$retryHint"
            else -> null
        }
    }
}
