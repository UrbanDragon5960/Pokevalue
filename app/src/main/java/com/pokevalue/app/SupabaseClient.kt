package com.pokevalue.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val expiresAt: Long
)

data class AuthResult(val session: SupabaseSession?, val message: String)

class SupabaseClient(context: Context) {
    private val prefs = context.getSharedPreferences("pokevalue_supabase_auth", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()

    fun isConfigured() = baseUrl.startsWith("https://") && publishableKey.isNotBlank()

    fun restoreSession(): SupabaseSession? {
        val access = prefs.getString("access_token", null) ?: return null
        val refresh = prefs.getString("refresh_token", null) ?: return null
        val user = prefs.getString("user_id", null) ?: return null
        val email = prefs.getString("email", "") ?: ""
        val expiry = prefs.getLong("expires_at", 0L)
        return SupabaseSession(access, refresh, user, email, expiry)
    }

    fun signIn(email: String, password: String): AuthResult =
        authenticate("/auth/v1/token?grant_type=password", JSONObject()
            .put("email", email.trim()).put("password", password))

    fun signUp(email: String, password: String): AuthResult =
        authenticate("/auth/v1/signup", JSONObject()
            .put("email", email.trim()).put("password", password))

    private fun authenticate(path: String, payload: JSONObject): AuthResult {
        val response = request("POST", path, payload, includeAuth = false)
        val obj = JSONObject(response)
        val access = obj.optString("access_token")
        val user = obj.optJSONObject("user")
        if (access.isBlank() || user == null || user.optString("id").isBlank()) {
            return AuthResult(null, "Account request accepted. Check your email for a confirmation link, then sign in.")
        }
        val session = SupabaseSession(
            access, obj.optString("refresh_token"), user.optString("id"),
            user.optString("email"), System.currentTimeMillis() / 1000L + obj.optLong("expires_in", 3600L)
        )
        saveSession(session)
        return AuthResult(session, "Signed in.")
    }

    fun signOut() {
        val session = restoreSession()
        if (session != null && isConfigured()) {
            runCatching { request("POST", "/auth/v1/logout", JSONObject(), session, includeAuth = true) }
        }
        prefs.edit().clear().apply()
    }

    fun loadCollection(original: SupabaseSession): List<Card> {
        val session = validSession(original)
        val query = "/rest/v1/collections?select=card_id,card_name,set_name,card_number,image_url,estimated_value_gbp,price_source,rarity,quantity&user_id=eq.${session.userId}&order=created_at.asc"
        val result = JSONArray(request("GET", query, session = session))
        return (0 until result.length()).mapNotNull { i ->
            val row = result.optJSONObject(i) ?: return@mapNotNull null
            Card(
                id = row.optString("card_id"),
                name = row.optString("card_name"),
                set = row.optString("set_name"),
                number = row.optString("card_number"),
                image = row.optString("image_url"),
                gbp = row.optDouble("estimated_value_gbp", 0.0),
                priceSource = row.optString("price_source"),
                rarity = row.optString("rarity"),
                quantity = row.optInt("quantity", 1)
            )
        }
    }

    fun saveCard(original: SupabaseSession, card: Card) {
        val session = validSession(original)
        val row = JSONObject()
            .put("user_id", session.userId)
            .put("card_id", card.id)
            .put("card_name", card.name)
            .put("set_name", card.set)
            .put("card_number", card.number)
            .put("image_url", card.image)
            .put("estimated_value_gbp", card.gbp)
            .put("price_source", card.priceSource)
            .put("rarity", card.rarity)
            .put("quantity", card.quantity)
        request(
            "POST",
            "/rest/v1/collections?on_conflict=user_id,card_id",
            row,
            session,
            extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        )
    }

    private fun validSession(original: SupabaseSession): SupabaseSession {
        // Auth refresh tokens rotate. Always prefer the latest persisted session so
        // repeated requests never try to reuse an already-rotated refresh token.
        val latest = restoreSession() ?: original
        if (latest.expiresAt > System.currentTimeMillis() / 1000L + 60L) return latest
        val payload = JSONObject().put("refresh_token", latest.refreshToken)
        val obj = JSONObject(request("POST", "/auth/v1/token?grant_type=refresh_token", payload, includeAuth = false))
        val user = obj.optJSONObject("user") ?: throw IOException("Your session expired. Please sign in again.")
        val renewed = SupabaseSession(
            obj.optString("access_token"), obj.optString("refresh_token"),
            user.optString("id"), user.optString("email"),
            System.currentTimeMillis() / 1000L + obj.optLong("expires_in", 3600L)
        )
        saveSession(renewed)
        return renewed
    }

    private fun saveSession(session: SupabaseSession) {
        prefs.edit()
            .putString("access_token", session.accessToken)
            .putString("refresh_token", session.refreshToken)
            .putString("user_id", session.userId)
            .putString("email", session.email)
            .putLong("expires_at", session.expiresAt)
            .apply()
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        session: SupabaseSession? = null,
        includeAuth: Boolean = session != null,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        if (!isConfigured()) throw IOException("Supabase is not configured. Add the project URL and publishable key to your local Gradle properties.")
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
        if (includeAuth && session != null) builder.header("Authorization", "Bearer ${session.accessToken}")
        extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        val requestBody = body?.toString()?.toRequestBody(jsonType)
        when (method) {
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody(jsonType))
            "GET" -> builder.get()
            else -> throw IOException("Unsupported request method.")
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message") } }.getOrNull()
                throw IOException(message?.takeIf { it.isNotBlank() } ?: "Supabase request failed (HTTP ${response.code}).")
            }
            return text
        }
    }
}
