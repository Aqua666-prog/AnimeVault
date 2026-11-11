package com.sergey.animevault.localai

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: normal regression suites do not download or require model weights. */
@RunWith(AndroidJUnit4::class)
class LocalAiDeviceProbe {
    @Test fun russianTwentyPrompts() = runBlocking {
        assumeTrue("Run with -Pandroid.testInstrumentationRunnerArguments.runLocalAi=true",
            InstrumentationRegistry.getArguments().getString("runLocalAi") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = File(context.noBackupFilesDir, "local-ai/model/config.json")
        assertTrue("Install verified official model files before running the probe", config.isFile)
        val engine = MnnLocalAiEngine(context)
        val rows = JSONArray()
        val report = JSONObject().put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("airplane_mode_confirmed_manually", JSONObject.NULL)
            .put("model_revision_verified_manually", JSONObject.NULL).put("results", rows)
        val output = File(context.noBackupFilesDir, "local-ai/probe.json")
        val peakPssKiB = AtomicInteger()
        val sampler = launch(Dispatchers.Default) {
            while (true) {
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                peakPssKiB.accumulateAndGet(info.totalPss, ::maxOf)
                delay(100)
            }
        }
        try {
            val loadStart = SystemClock.elapsedRealtimeNanos()
            engine.load(config)
            report.put("load_ms_wall", (SystemClock.elapsedRealtimeNanos() - loadStart) / 1_000_000.0)
            for (prompt in russianPrompts) {
                val row = JSONObject().put("prompt", prompt).put("human_score_0_to_4", JSONObject.NULL)
                rows.put(row)
                val start = SystemClock.elapsedRealtimeNanos()
                var first: Long? = null
                val text = StringBuilder()
                try {
                    withTimeout(180_000) {
                        engine.generate(listOf(AiMessage(AiMessage.Role.USER, prompt))).collect { delta ->
                            if (first == null) first = SystemClock.elapsedRealtimeNanos()
                            text.append(delta)
                        }
                    }
                    row.put("response", text.toString())
                    row.put("ttft_ms_wall", first?.let { (it - start) / 1_000_000.0 } ?: JSONObject.NULL)
                    row.put("total_ms_wall", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                    engine.statistics.value?.let {
                        row.put("generated_tokens_including_eos", it.generatedTokens)
                            .put("prefill_us", it.prefillMicros).put("decode_us", it.decodeMicros)
                            .put("mnn_decode_tokens_per_second", if (it.decodeMicros > 0)
                                it.generatedTokens * 1_000_000.0 / it.decodeMicros else JSONObject.NULL)
                    }
                } catch (e: Exception) {
                    row.put("error", e.javaClass.simpleName)
                    throw e
                } finally {
                    output.writeText(report.toString(2))
                }
            }
            assertTrue("No probe response may be empty", (0 until rows.length()).all {
                rows.getJSONObject(it).optString("response").isNotBlank()
            })
        } finally {
            sampler.cancelAndJoin()
            report.put("whole_process_peak_sampled_pss_kib", peakPssKiB.get())
            try { output.writeText(report.toString(2)) } finally { engine.unload() }
        }
    }
}

private val russianPrompts = listOf(
    "Привет! Ответь одним предложением по-русски.",
    "Объясни простыми словами, что такое аниме.",
    "Посоветуй психологический хоррор. Если у тебя нет каталога, попроси его предоставить.",
    "Хочу аниме с сильной героиней и трагическим сюжетом. Каких данных тебе не хватает для поиска?",
    "Найди что-нибудь похожее на Higurashi. Не выдумывай названия без каталога.",
    "Переформулируй запрос: хочу что-нибудь страшное, но без жестокости.",
    "Ответь ровно тремя словами: какое настроение у дождливого вечера?",
    "Назови два отличия комедии от трагедии.",
    "Составь короткий вопрос о предпочтениях зрителя.",
    "Переведи на русский: psychological horror with a tragic ending.",
    "Что означает слово «экранизация»?",
    "Объясни разницу между сезоном и серией в двух предложениях.",
    "Я не хочу спойлеров. Как спросить о сюжете без раскрытия концовки?",
    "Сократи фразу «я хотел бы посмотреть анимационный сериал».",
    "Исправь ошибку: «Мне нравиться смотреть аниме».",
    "Продолжи диалог: — Какой жанр тебе нравится? — Психологическая драма. —",
    "Как вежливо сообщить, что подходящих произведений в каталоге нет?",
    "Каталог пуст. Можно ли достоверно назвать рейтинг неизвестного произведения? Объясни кратко.",
    "Ответь по-русски: чем отличается личное мнение от проверенного факта?",
    "Суммируй одним предложением: зритель любит ужасы, не любит комедии и хочет короткий сериал.",
)
