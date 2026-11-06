// src\oss
package com.aryan.reader.data

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
data class FeedbackTextPayload(
    val message: String,
    val category: String,
    val context: Map<String, String>
)

@Serializable
data class FeedbackTextResponse(
    val status: String,
    val thread_ts: String? = null,
    val message: String? = null
)

class FeedbackRepository(private val context: Context) {

    private val firestoreRepository = FirestoreRepository()

    fun sendFeedbackText(payload: FeedbackTextPayload): Result<FeedbackTextResponse> {
        return Result.failure(Exception("Feedback submission not available in OSS version"))
    }

    fun sendFeedbackImage(imageUri: Uri, threadTs: String): Result<Unit> {
        return Result.failure(Exception("Feedback submission not available in OSS version"))
    }

    // --- Feedback / Support System Stubs ---

    fun listenForUnreadFeedback(userId: String, onUpdate: (Boolean) -> Unit): Any? {
        return firestoreRepository.listenForUnreadFeedback(userId, onUpdate)
    }

    fun listenToFeedbackThreads(userId: String, onUpdate: (List<FeedbackThread>) -> Unit): Any? {
        return firestoreRepository.listenToFeedbackThreads(userId, onUpdate)
    }

    fun listenToMessages(threadId: String, onUpdate: (List<FeedbackMessage>) -> Unit): Any? {
        return firestoreRepository.listenToFeedbackMessages(threadId, onUpdate)
    }

    fun generateMessageId(): String {
        return firestoreRepository.generateMessageId()
    }

    suspend fun createThread(userId: String, category: String, message: String, attachmentUris: List<Uri>): String {
        return firestoreRepository.createFeedbackThread(userId, category, message, emptyList())
    }

    suspend fun addMessage(threadId: String, messageId: String, uid: String, message: String, sender: String, attachmentUris: List<Uri> = emptyList()) {
        firestoreRepository.addMessageToThread(threadId, messageId, uid, message, sender, emptyList())
    }

    suspend fun markThreadAsRead(threadId: String) {
        firestoreRepository.markThreadAsRead(threadId)
    }

    fun removeListener(listener: Any?) {
        firestoreRepository.removeListener(listener)
    }
}