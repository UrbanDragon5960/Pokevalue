package com.pokevalue.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class SupabaseSession(val accessToken: String, val userId: String)

class SupabaseBackend {
    private val client = OkHttpClient()
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    fun isConfigured(): Boolean = baseUrl.startsWith("https://") && anonKey.isNotBlank()

    fun signIn(email: String, password: String): SupabaseSession =
        authenticate("auth/v1/token?grant_type=password", email, password)

    fun signUp(email: String, password: String): SupabaseSession =
        authenticate("auth/v1/signup", email, password)

    private fun authenticate(path: String, email: String, password: String): SupabaseSession {
        val body = JSONObject().put("email", email).put("password", password)
        val response = execute(
            Request.Builder().url("$baseUrl/$path")
                .header("apikey", anonKey)
                .post(body.toString().jsonBody())
                .build()
        )
        val json = JSONObject(response)
        val user = json.optJSONObject("user") ?: json
        return SupabaseSession(json.optString("access_token"), user.optString("id"))
    }

    fun loadCards(token: String): List<Card> {
        val response = execute(
            Request.Builder().url("$baseUrl/rest/v1/user_cards?select=card_data")
                .header("apikey", anonKey).header("Authorization", "Bearer $token")
                .get().build()
        )
        val rows = JSONArray(response)
        return (0 until rows.length()).mapNotNull { index ->
            val data = rows.optJSONObject(index)?.optJSONObject("card_data") ?: return@mapNotNull null
            Card(
                id = data.optString("id"), name = data.optString("name"),
                set = data.optString("set"), number = data.optString("number"),
                image = data.optString("image"), gbp = data.optDouble("gbp", 0.0),
                priceSource = data.optString("priceSource"), rarity = data.optString("rarity"),
                confidence = data.optInt("confidence", 0), quantity = data.optInt("quantity", 1)
            )
        }
    }

    fun saveCards(token: String, userId: String, cards: List<Card>) {
        cards.forEach { upsertCard(token, userId, it) }
    }

    fun upsertCard(token: String, userId: String, card: Card) {
        val data = JSONObject()
            .put("id", card.id).put("name", card.name).put("set", card.set)
            .put("number", card.number).put("image", card.image).put("gbp", card.gbp)
            .put("priceSource", card.priceSource).put("rarity", card.rarity)
            .put("confidence", card.confidence).put("quantity", card.quantity)
        val row = JSONArray().put(JSONObject().put("user_id", userId).put("card_id", card.id).put("card_data", data))
        execute(
            Request.Builder().url("$baseUrl/rest/v1/user_cards?on_conflict=user_id,card_id")
                .header("apikey", anonKey).header("Authorization", "Bearer $token")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(row.toString().jsonBody()).build()
        )
    }

    fun deleteCard(token: String, userId: String, cardId: String) {
        val safeUser = java.net.URLEncoder.encode(userId, "UTF-8")
        val safeCard = java.net.URLEncoder.encode(cardId, "UTF-8")
        execute(
            Request.Builder().url("$baseUrl/rest/v1/user_cards?user_id=eq.$safeUser&card_id=eq.$safeCard")
                .header("apikey", anonKey).header("Authorization", "Bearer $token")
                .delete().build()
        )
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val error = JSONObject(body)
                    error.optString("msg").ifBlank {
                        error.optString("message").ifBlank { error.optString("error_description") }
                    }
                }.getOrDefault("")
                throw IOException(detail.ifBlank { "Supabase returned HTTP ${response.code}." })
            }
            return body
        }
    }

    private fun String.jsonBody() = toRequestBody("application/json; charset=utf-8".toMediaType())
}
