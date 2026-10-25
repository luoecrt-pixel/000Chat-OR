package com.example.openaichat

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Runtime never retains an Activity or a view; a recreated Activity can subscribe again. */
object OpenRouterUiBridge {
    interface Host {
        fun responseChanged(chatId: String, requestId: String)
        fun historyReconciled(chatId: String, requestId: String) {}
        fun protocolWarning(chatId: String, requestId: String, warning: String)
        fun confirmTool(chatId: String, requestId: String, name: String, arguments: JSONObject): Boolean
        fun isViewing(chatId: String): Boolean
        fun ownsClient(client: OpenRouterChatClient): Boolean
    }
    @Volatile private var host = WeakReference<Host>(null)
    private val handler = Handler(Looper.getMainLooper())
    fun attach(value: Host) { host = WeakReference(value) }
    fun detach(value: Host) { if (host.get() === value) host.clear() }
    fun changed(chatId: String, requestId: String) { handler.post { host.get()?.responseChanged(chatId, requestId) } }
    fun historyReconciled(chatId: String, requestId: String) { handler.post { host.get()?.historyReconciled(chatId, requestId) } }
    fun warning(chatId: String, requestId: String, warning: String) { handler.post { host.get()?.protocolWarning(chatId, requestId, warning) } }
    fun confirm(chatId: String, requestId: String, name: String, arguments: JSONObject) =
        host.get()?.confirmTool(chatId, requestId, name, arguments) == true
    fun viewing(chatId: String) = host.get()?.isViewing(chatId) == true
    fun ownsClient(client: OpenRouterChatClient) = host.get()?.ownsClient(client) == true
}
