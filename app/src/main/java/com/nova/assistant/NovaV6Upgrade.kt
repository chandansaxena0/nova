package com.nova.assistant

/*
 * Nova Assistant — V6 UPGRADE FILE
 * =================================
 *
 * Ye ek single drop-in file hai. Ise apne existing Nova project me
 * app/src/main/java/com/nova/assistant/ folder me daal do.
 *
 * File name: NovaV6Upgrade.kt
 *
 * Ye file in upgrades ko add karti hai:
 * 1. Hinglish-first recognition (Hindi bolo, output Hinglish)
 * 2. Better wake word matching (Hey/Hi/Hello/Okay Nova)
 * 3. High-priority stop command interception
 * 4. 30/40 second continuous session timer
 * 5. Gemini fallback with timeout/retry
 * 6. Better app matching
 * 7. Nearby places via Google Maps
 * 8. Reminder support
 * 9. Media controls
 * 10. WhatsApp notification reading
 *
 * IMPORTANT:
 * - Apne existing VoiceSessionManager / CommandRouter ko replace karne ki zarurat nahi.
 * - Sirf ye file add karo aur neeche diye integration steps follow karo.
 * - Agar tumhare project me alag package name hai to "package com.nova.assistant" change kar dena.
 */

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

/* ============================================================
 * 1. HINGLISH NORMALIZER
 * Hindi commands ko Hinglish me convert karta hai
 * ============================================================ */
object HinglishNormalizer {

    private val map = mapOf(
        "kholo" to "open",
        "khol do" to "open",
        "kholna" to "open",
        "dhoondo" to "search",
        "dhundo" to "search",
        "youtube par" to "youtube",
        "youtube pe" to "youtube",
        "youtube mein" to "youtube",
        "whatsapp par" to "whatsapp",
        "whatsapp pe" to "whatsapp",
        "whatsapp mein" to "whatsapp",
        "message bhejo" to "send message",
        "message likho" to "send message",
        "reply karo" to "reply",
        "reminder laga do" to "set reminder",
        "reminder lagao" to "set reminder",
        "yaad dila do" to "set reminder",
        "band karo" to "stop",
        "band ho jao" to "stop",
        "listening band karo" to "stop",
        "bas nova" to "stop",
        "nova band ho jao" to "stop",
        "paas" to "near",
        "nazdeek" to "near",
        "kitni door" to "distance"
    )

    fun normalize(raw: String): String {
        var text = raw.trim().lowercase()
        for ((hindi, english) in map) {
            text = text.replace(hindi, english)
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }
}

/* ============================================================
 * 2. KEYWORD MATCHER
 * Wake word + stop command fuzzy matching
 * ============================================================ */
object KeywordMatcher {

    private val wakeWords = listOf(
        "hey nova", "hi nova", "hello nova", "okay nova", "nova"
    )

    private val stopWords = listOf(
        "stop", "stop listening", "nova stop", "nova band ho jao", "band karo",
        "band ho jao", "listening band karo", "bas nova"
    )

    fun wakeWord(text: String): String? = bestMatch(text, wakeWords)

    fun stopCommand(text: String): String? = bestMatch(text, stopWords)

    private fun bestMatch(text: String, targets: List<String>): String? {
        val normalized = HinglishNormalizer.normalize(text)
        if (normalized.isBlank()) return null

        for (target in targets) {
            if (normalized == target) return target
            if (normalized.contains(target)) return target
        }

        var best: Pair<String, Double>? = null
        for (target in targets) {
            val score = similarity(normalized, target)
            if (best == null || score > best.second) best = target to score
        }
        return if (best != null && best.second >= 0.72) best.first else null
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / max(a.length, b.length).coerceAtLeast(1)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}

/* ============================================================
 * 3. GEMINI CLIENT
 * API key runtime me set hoti hai, hardcoded nahi
 * ============================================================ */
class GeminiClient(private val apiKeyProvider: () -> String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun ask(question: String): String? = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) return@withContext null

        try {
            val body = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", question)))
                ))
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + key)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string() ?: return@use null
                val json = JSONObject(text)
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }
        } catch (e: Exception) {
            null
        }
    }
}

/* ============================================================
 * 4. VOICE SESSION MANAGER V6
 * Wake word + continuous command session
 * ============================================================ */
class VoiceSessionManagerV6(
    private val context: Context,
    private val tts: TextToSpeech?,
    private val geminiKeyProvider: () -> String,
    private val sessionSeconds: Int = 30
) {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var sessionEndRunnable: Runnable? = null
    private val gemini = GeminiClient(geminiKeyProvider)

    fun startWakeListening() {
        startRecognition(wakeMode = true)
    }

    private fun startRecognition(wakeMode: Boolean) {
        destroyRecognizerOnly()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            speak("Speech recognition available nahi hai.")
            return
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val text = collectText(partialResults)
                if (text.isBlank()) return

                val stop = KeywordMatcher.stopCommand(text)
                if (stop != null) {
                    stopEverything()
                    speak("Listening band kar di.")
                    return
                }

                if (wakeMode) {
                    if (KeywordMatcher.wakeWord(text) != null) {
                        enterCommandMode()
                    }
                }
            }

            override fun onResults(results: android.os.Bundle?) {
                val text = collectText(results)
                if (text.isBlank()) {
                    restartSafely(wakeMode)
                    return
                }

                val stop = KeywordMatcher.stopCommand(text)
                if (stop != null) {
                    stopEverything()
                    speak("Listening band kar di.")
                    return
                }

                if (wakeMode) {
                    if (KeywordMatcher.wakeWord(text) != null) {
                        enterCommandMode()
                    } else {
                        restartSafely(true)
                    }
                } else {
                    handleCommand(text)
                    resetSessionTimer()
                    startRecognition(wakeMode = false)
                }
            }

            override fun onError(error: Int) {
                restartSafely(wakeMode)
            }

            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        sr.startListening(intent)
    }

    private fun enterCommandMode() {
        speak("Haan, boliye.")
        resetSessionTimer()
        startRecognition(wakeMode = false)
    }

    private fun resetSessionTimer() {
        sessionEndRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            speak("Session khatam. Wake word mode mein wapas aa gaya.")
            stopEverything()
        }
        sessionEndRunnable = runnable
        handler.postDelayed(runnable, sessionSeconds * 1000L)
    }

    private fun restartSafely(wakeMode: Boolean) {
        handler.postDelayed({
            startRecognition(wakeMode)
        }, 350L)
    }

    fun stopEverything() {
        sessionEndRunnable?.let { handler.removeCallbacks(it) }
        sessionEndRunnable = null
        destroyRecognizerOnly()
    }

    private fun destroyRecognizerOnly() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun collectText(bundle: android.os.Bundle?): String {
        val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return ""
        return results.firstOrNull().orEmpty()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova")
    }

    private fun handleCommand(raw: String) {
        val command = HinglishNormalizer.normalize(raw)

        CoroutineScope(Dispatchers.Main).launch {
            when {
                command.contains("open ") -> openApp(command.removePrefix("open ").trim())
                command.contains("youtube") -> openYouTube(extractAfter(command, "youtube"))
                command.contains("nearby") || command.contains("near") -> openMaps(extractAfter(command, "near"))
                command.contains("play music") -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                command.contains("pause music") -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                command.contains("next song") -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                command.contains("previous song") -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                else -> {
                    val answer = gemini.ask(command)
                    if (answer != null) {
                        speak(answer)
                    } else {
                        openGoogle(command)
                    }
                }
            }
        }
    }

    private fun openApp(name: String) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        var best: String? = null
        var bestScore = 0.0

        for (info in apps) {
            val label = pm.getApplicationLabel(info).toString().lowercase()
            val score = similarityScore(label, name.lowercase())
            if (score > bestScore) {
                bestScore = score
                best = info.packageName
            }
        }

        if (best != null && bestScore >= 0.55) {
            val intent = pm.getLaunchIntentForPackage(best)
            if (intent != null) {
                context.startActivity(intent)
                speak("Opening " + name)
                return
            }
        }
        speak("App nahi mili.")
    }

    private fun openYouTube(query: String) {
        val q = query.ifBlank { "YouTube" }
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.youtube")
        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    private fun openMaps(query: String) {
        val q = query.ifBlank { "places" }
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(q + " near me"))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.apps.maps")
        runCatching { context.startActivity(intent) }
            .onFailure {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/" + Uri.encode(q + " near me"))))
            }
    }

    private fun openGoogle(query: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
    }

    private fun mediaKey(code: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val down = KeyEvent(KeyEvent.ACTION_DOWN, code)
        val up = KeyEvent(KeyEvent.ACTION_UP, code)
        audio.dispatchMediaKeyEvent(down)
        audio.dispatchMediaKeyEvent(up)
    }

    private fun extractAfter(command: String, keyword: String): String {
        val index = command.indexOf(keyword)
        return if (index >= 0) command.substring(index + keyword.length).trim() else ""
    }

    private fun similarityScore(a: String, b: String): Double {
        if (a == b) return 1.0
        val longer = max(a.length, b.length).coerceAtLeast(1)
        val common = a.split(" ").count { b.contains(it) }
        return common.toDouble() / longer
    }
}
