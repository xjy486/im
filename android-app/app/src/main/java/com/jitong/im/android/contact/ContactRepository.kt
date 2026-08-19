package com.jitong.im.android.contact

import retrofit2.Response
import java.io.IOException
import java.util.UUID

internal class ContactRepository(
    private val api: ContactApi,
) {
    suspend fun search(accountNo: String): ContactSearchResult = api.search(accountNo).bodyOrThrow()

    suspend fun createRequest(accountNo: String, verification: String): ContactRequestResponse =
        api.createRequest(CreateContactRequest(accountNo, verification)).bodyOrThrow()

    suspend fun requests(): List<ContactRequestSummary> = api.requests().bodyOrThrow()

    suspend fun accept(requestId: UUID): ContactRequestResponse = api.accept(requestId).bodyOrThrow()

    suspend fun reject(requestId: UUID): ContactRequestResponse = api.reject(requestId).bodyOrThrow()

    suspend fun cancel(requestId: UUID): ContactRequestResponse = api.cancel(requestId).bodyOrThrow()

    suspend fun contacts(): List<ContactSummary> = api.contacts().bodyOrThrow()

    suspend fun remove(userId: UUID) {
        api.remove(userId).requireSuccess()
    }

    suspend fun block(userId: UUID) {
        api.block(userId).requireSuccess()
    }

    suspend fun unblock(userId: UUID) {
        api.unblock(userId).requireSuccess()
    }

    suspend fun conversations(): List<ConversationSummary> = api.conversations().bodyOrThrow()

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (isSuccessful && body() != null) return body()!!
        throw IOException("Contact request failed with HTTP ${code()}")
    }

    private fun Response<Void>.requireSuccess() {
        if (!isSuccessful) throw IOException("Contact request failed with HTTP ${code()}")
    }
}
