package com.example.anonymous.community

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.network.model.Community
import com.example.anonymous.network.model.CommunityMessage
import com.example.anonymous.repository.CommunityRepository
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class ParsedCommunityInvite(
    val b32Address: String,
    val communityName: String,
    val groupKeyBase64: String
)

object CommunityInvite {
    private const val TAG = "CommunityInvite"
    private const val SCHEME = "anonymous-community"

    // anonymous-community://<b32Address>?name=<encoded>&key=<groupKeyBase64>
    fun generateInviteUri(community: Community): String = buildString {
        append("$SCHEME://")
        append(community.b32Address)
        append("?name=")
        append(URLEncoder.encode(community.name, "UTF-8"))
        append("&key=")
        append(URLEncoder.encode(community.groupKeyBase64, "UTF-8"))
    }

    fun parseInviteUri(content: String): ParsedCommunityInvite? {
        return try {
            if (!content.startsWith("$SCHEME://")) return null

            val uri = URI(content)
            val b32 = uri.host ?: return null
            val params = uri.rawQuery
                ?.split("&")
                ?.mapNotNull { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
                }
                ?.toMap() ?: emptyMap()

            val name = params["name"]?.takeIf { it.isNotBlank() } ?: return null
            val key = params["key"]?.takeIf { it.isNotBlank() } ?: return null

            val keyBytes = Base64.decode(key, Base64.NO_WRAP)
            if (keyBytes.size != 32) {
                Log.w(TAG, "Invite key is ${keyBytes.size} bytes - expected 32")
                return null
            }

            ParsedCommunityInvite(b32Address = b32, communityName = name, groupKeyBase64 = key)
        } catch (e : Exception) {
            Log.e(TAG, "Failed to parse community invite: ${e.message}")
            null
        }
    }

    fun isCommunityInvite(content: String) = content.startsWith("$SCHEME://")
}

suspend fun createCommunity(
    context: Context,
    name: String,
): Result<Community> {
    return try {
        val sam = SAMClient.getInstance()

        // Generate permanent I2P keypair
        val (pubKey, privKey) = sam.generateDestination().getOrThrow()

        val session = sam.createStreamSession(privKey).getOrThrow()
        val b32 = session.b32Address
        sam.removeSession(session.id)

        val community = Community(
            name = name,
            b32Address = b32,
            samPrivateKey = privKey,
            groupKeyBase64 = Base64.encodeToString(CommunityEncryption.generateGroupKey(), Base64.NO_WRAP),
            isCreator = true
        )

        CommunityRepository.getInstance(context).save(community)

        CommunityHostService.start(context, community.b32Address)

        Log.i("createCommunity", "Community '${name}' created at $b32")
        Result.success(community)
    } catch (e : Exception) {
        Result.failure(e)
    }
}

suspend fun joinCommunity(
    context: Context,
    invite: ParsedCommunityInvite,
    myB32: String,
    myName: String,
    onMessage: (CommunityMessage) -> Unit
): Result<Pair<Community, CommunityMemberClient>> {
    return try {
        val community = Community(
            name = invite.communityName,
            b32Address = invite.b32Address,
            samPrivateKey = null,           // Members never hold the private key
            groupKeyBase64 = invite.groupKeyBase64,
            isCreator = false
        )

        CommunityRepository.getInstance(context).save(community)

        val client = CommunityMemberClient(
            community = community,
            myB32 = myB32,
            myName = myName,
            onMessage = onMessage
        )
        client.connect()

        Result.success(Pair(community, client))
    } catch (e : Exception) {
        Result.failure(e)
    }
}