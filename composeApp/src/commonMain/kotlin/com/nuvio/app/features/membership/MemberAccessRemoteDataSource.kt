package com.nuvio.app.features.membership

import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable

internal object MemberAccessRemoteDataSource {
    suspend fun getMemberAccess(): MemberAccess {
        return MemberAccess.All
    }
}

@Serializable
private data class MemberAccessResponse(
    val tier: String,
    val entitlements: List<String>,
)
