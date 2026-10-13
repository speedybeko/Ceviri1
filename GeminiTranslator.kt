package com.berkay.oyunceviri

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gemini ile bağlam duyarlı İngilizce -> Türkçe altyazı çevirisi.
 * Önceki 3 altyazı satırı bağlam olarak gönderilir, aynı metin önbellekten gelir.
 *
 * onDone(sonuç, kod): kod 200 = başarılı, 0 = ağ/ayrıştırma hatası, diğerleri HTTP kodu.
 * onDone, OkHttp'nin arka plan thread'inde çağrılır.
 */
class GeminiTranslator(
    private val apiKey: String,
    private val model: String,
    private val gameHint: String
) {

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private const val SYSTEM =
            "You are a subtitle translator for video games. Translate the given English subtitle " +
                "into natural, colloquial Turkish, the way a good game localizer would. " +
                "Output ONLY the Turkish translation: no quotes, notes or explanations. " +
                "The text comes from OCR and may contain small recognition errors; silently fix obvious ones. " +
                "Keep character names, place names and proper nouns unchanged. " +
                "Keep it short enough to read quickly. " +
                "Previous lines are context only: never translate or repeat them."
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private val history = ArrayDeque<String>()
    private val cache = HashMap<String, String>()

    fun close() {
        client.dispatcher.executorService.shutdown()
    }

    fun translate(text: String, onDone: (String?, Int) -> Unit) {
        val cached = synchronized(lock) { cache[text] }
        if (cached != null) {
            onDone(cached, 200)
            return
        }

        val url = try {
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                .toHttpUrl().newBuilder().addQueryParameter("key", apiKey).build()
        } catch (e: IllegalArgumentException) {
            onDone(null, 400)
            return
        }
        val request = Request.Builder()
            .url(url)
            .post(buildBody(text).toString().toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onDone(null, 0)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        onDone(null, r.code)
                        return
                    }
                    val out = parse(body)
                    if (out == null) {
                        onDone(null, 0) // güvenlik filtresi vb.: boş yanıt
                        return
                    }
                    synchronized(lock) {
                        if (cache.size > 200) cache.clear()
                        cache[text] = out
                        history.addLast(text)
                        while (history.size > 3) history.removeFirst()
                    }
                    onDone(out, 200)
                }
            }
        })
    }

    private fun buildBody(text: String): JSONObject {
        val prev = synchronized(lock) { history.toList() }
        val prompt = buildString {
            if (gameHint.isNotEmpty()) append("Game: ").append(gameHint).append("\n\n")
            if (prev.isNotEmpty()) {
                append("Previous subtitle lines (context only, do not translate them):\n")
                for (p in prev) append("- ").append(p).append('\n')
                append('\n')
            }
            append("Translate this subtitle into Turkish:\n").append(text)
        }
        val sys = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
        val user = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        return JSONObject()
            .put("systemInstruction", sys)
            .put("contents", JSONArray().put(user))
            .put("generationConfig", JSONObject().put("temperature", 0.3))
    }

    private fun parse(body: String): String? {
        return try {
            val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            sb.toString().trim().removeSurrounding("\"").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
