package me.rerere.rikkahub.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.AppLogBuffer
import kotlin.concurrent.thread

/**
 * 语音唤醒（KWS）常驻服务 —— 2026-11-10
 *
 * 第一版目的只有一个：**验证听不听得见**。
 * 命中时只往 AppLogBuffer 打一行日志，不接 ChatService、不发消息。
 * 听得到再接触发链路，那样出了问题能分清是"没听见"还是"听见了但没接上"。
 *
 * 模型：sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01（int8）
 * 放在 assets/kws/<模型目录>/ 下，由 AssetManager 直接读。
 */
class VoiceWakeService : Service() {

    companion object {
        private const val TAG = "VoiceWake"

        const val CHANNEL_ID = "voice_wake"
        const val NOTIFICATION_ID = 0x7E4A

        /** assets 里的模型目录 */
        private const val MODEL_DIR = "kws/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
        private const val KEYWORDS_FILE = "$MODEL_DIR/keywords.txt"

        private const val SAMPLE_RATE = 16000

        fun start(context: Context) {
            val i = Intent(context, VoiceWakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceWakeService::class.java))
        }
    }

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var spotter: KeywordSpotter? = null
    private var record: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            startForegroundCompat()
            running = true
            worker = thread(name = "voice-wake", isDaemon = true) { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        try {
            record?.release()
        } catch (_: Throwable) {
        }
        record = null
        worker?.join(1500)
        worker = null
        try {
            spotter?.release()
        } catch (_: Throwable) {
        }
        spotter = null
        log("service destroyed")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "语音唤醒",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "常驻监听唤醒词，不响铃不震动"
                setShowBadge(false)
            }
        )
    }

    private fun startForegroundCompat() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("小猫在听")
            .setContentText("说「小猫小猫」可以叫醒它")
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    // ---------------------------------------------------------------- 主循环

    private fun loop() {
        log("loop start")

        val s = buildSpotter()
        if (s == null) {
            log("spotter build FAILED, give up")
            stopSelf()
            return
        }
        spotter = s
        log("spotter ready, keywords=$KEYWORDS_FILE")

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            log("getMinBufferSize failed: $minBuf")
            stopSelf()
            return
        }
        val bufSize = minBuf * 2

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (t: Throwable) {
            log("AudioRecord ctor threw: ${t.message}")
            stopSelf()
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            log("AudioRecord not initialized, state=${rec.state}")
            rec.release()
            stopSelf()
            return
        }

        record = rec
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            log("startRecording threw: ${t.message}")
            stopSelf()
            return
        }
        log("recording started, bufSize=$bufSize")

        val stream = s.createStream()
        val shorts = ShortArray(bufSize / 2)
        var readErrorStreak = 0

        while (running) {
            val n = rec.read(shorts, 0, shorts.size)
            if (n <= 0) {
                readErrorStreak++
                if (readErrorStreak in listOf(1, 10, 100)) {
                    log("read returned $n (streak=$readErrorStreak)")
                }
                if (readErrorStreak > 500) {
                    log("read keeps failing, abort")
                    break
                }
                continue
            }
            readErrorStreak = 0

            val samples = FloatArray(n)
            for (i in 0 until n) {
                samples[i] = shorts[i] / 32768.0f
            }

            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (s.isReady(stream)) {
                    s.decode(stream)
                }
                val r = s.getResult(stream)
                if (r.keyword.isNotEmpty()) {
                    log("WAKE!! keyword=${r.keyword}")
                    s.reset(stream)
                    onWake(r.keyword)
                }
            } catch (t: Throwable) {
                log("decode threw: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        try {
            rec.stop()
        } catch (_: Throwable) {
        }
        rec.release()
        record = null
        log("loop end")
    }

    private fun buildSpotter(): KeywordSpotter? {
        return try {
            val config = KeywordSpotterConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        decoder = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        joiner = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    ),
                    tokens = "$MODEL_DIR/tokens.txt",
                    modelType = "zipformer2",
                ),
                keywordsFile = KEYWORDS_FILE,
                keywordsScore = 1.5f,
                keywordsThreshold = 0.25f,
            )
            KeywordSpotter(assetManager = assets, config = config)
        } catch (t: Throwable) {
            log("buildSpotter threw: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * 唤醒之后干什么 —— 第一版只打日志。
     * 验证通过后再接 ChatService.sendMessage(...)。
     */
    private fun onWake(keyword: String) {
        log("onWake($keyword) —— 第一版到此为止，不接聊天")
    }

    private fun log(msg: String) {
        AppLogBuffer.log(TAG, msg)
    }
}
