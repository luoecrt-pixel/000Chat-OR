package com.example.openaichat

import org.json.JSONObject
import java.io.IOException

/** A persistence boundary failure must abort the tool loop, never become a tool error reply. */
class LocalArtifactOutcomeUnknownException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** SQLite authorizes publication; recovery verifies existing bytes and never executes the tool again. */
class LocalArtifactIntentCoordinator(
    private val db: ChatDatabase,
    private val executor: LocalArtifactToolExecutor,
    private val chatId: String,
    private val requestId: String,
    private val active: () -> Boolean
) {
    fun recoverStored(record: JSONObject, onProtocolReconciled: () -> Unit = {}): JSONObject {
        if (record.getString("chat_id") != chatId || record.getString("request_id") != requestId)
            throw LocalArtifactOutcomeUnknownException("Намерение файла принадлежит другому запросу")
        return recover(record, onProtocolReconciled)
    }

    fun execute(callId: String, name: String, arguments: JSONObject,
        files: List<OpenRouterLocalAttachment>): JSONObject {
        val argumentsSha = StreamingJsonRequestBody.sha256(arguments)
        val prior = boundary { db.getLocalArtifactIntent(chatId, requestId, callId) }
        if (prior != null) {
            if (prior.getString("tool_name") != name || prior.getString("arguments_sha256") != argumentsSha)
                throw LocalArtifactOutcomeUnknownException("Сохранённый вызов инструмента имеет другую идентичность")
            return recover(prior)
        }
        if (!active()) throw LocalArtifactOutcomeUnknownException("Запрос остановлен до публикации файла")
        // Preparation writes staging bytes only. It cannot publish an unclaimed artifact.
        val key = "$chatId\u0000$requestId\u0000$callId"
        val plan = if (name == "patch_local_file") executor.preparePatchLocalFile(arguments, files, key, active)
            else executor.prepareTextFile(arguments, key)
        plan.use {
            val record = boundary {
                db.beginLocalArtifactIntentIfActive(chatId, requestId, callId, name, argumentsSha, plan.snapshot)
            } ?: throw LocalArtifactOutcomeUnknownException("Не подтверждено сохранение намерения создать файл")
            if (!record.optBoolean("_inserted")) return recover(record)
            return try {
                val outcome = plan.publish(active)
                complete(record, outcome)
            } catch (error: Exception) {
                // Publication may already have happened. Retain target and diff even after Stop/SQL errors.
                try { markUnknown(record) } catch (_: Exception) { /* The persisted in_flight intent remains truthful. */ }
                if (error is LocalArtifactOutcomeUnknownException) throw error
                throw LocalArtifactOutcomeUnknownException("Результат создания файла требует локальной проверки", error)
            }
        }
    }

    private fun recover(record: JSONObject, onProtocolReconciled: () -> Unit = {}): JSONObject {
        if (record.getBoolean("tombstoned"))
            throw LocalArtifactOutcomeUnknownException("Файл относится к удалённому чату или удалённой локальной копии")
        val verified = boundary { executor.recoverPublished(record.getJSONObject("plan")) }
        if (verified == null) {
            boundary { markUnknown(record) }
            throw LocalArtifactOutcomeUnknownException("Результат предыдущего вызова неизвестен; повторное исполнение запрещено")
        }
        val outcome = complete(record, verified)
        val reconciled = boundary {
            db.reconcileLocalArtifactProtocolOutcome(chatId, requestId, record.getString("tool_call_id"),
                record.getString("tool_name"), record.getString("arguments_sha256"),
                record.getString("plan_sha256"))
        }
        if (reconciled) onProtocolReconciled()
        return outcome
    }

    private fun complete(record: JSONObject, outcome: JSONObject): JSONObject {
        val completed = boundary {
            db.completeLocalArtifactIntent(chatId, requestId, record.getString("tool_call_id"),
                record.getString("tool_name"), record.getString("arguments_sha256"),
                record.getString("plan_sha256"), outcome)
        }
        if (completed == null || completed.getBoolean("tombstoned") || completed.getString("state") != "completed")
            throw LocalArtifactOutcomeUnknownException("Файл сохранён, но его результат не подтверждён в истории")
        return completed.getJSONObject("outcome")
    }

    private fun markUnknown(record: JSONObject) = db.markLocalArtifactIntentUnknown(chatId, requestId,
        record.getString("tool_call_id"), record.getString("tool_name"),
        record.getString("arguments_sha256"), record.getString("plan_sha256"))

    private inline fun <T> boundary(action: () -> T): T = try { action() }
        catch (error: LocalArtifactOutcomeUnknownException) { throw error }
        catch (error: Exception) { throw LocalArtifactOutcomeUnknownException("Не удалось подтвердить локальный результат инструмента", error) }
}
