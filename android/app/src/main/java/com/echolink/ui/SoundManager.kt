package com.echolink.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.echolink.R

/**
 * 发送音效管理：消息发送"嗒"声、语音发送"唰"声
 */
object SoundManager {

    @Volatile
    private var sp: SoundPool? = null
    private var sendId = 0
    private var voiceId = 0

    @JvmStatic
    fun init(context: Context) {
        if (sp != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()
        sendId = pool.load(context, R.raw.sfx_send, 1)
        voiceId = pool.load(context, R.raw.sfx_voice, 1)
        sp = pool
    }

    @JvmStatic
    fun playMessageSent() {
        val p = sp ?: return
        if (sendId != 0) p.play(sendId, 1f, 1f, 1, 0, 1f)
    }

    @JvmStatic
    fun playVoiceSent() {
        val p = sp ?: return
        if (voiceId != 0) p.play(voiceId, 1f, 1f, 1, 0, 1f)
    }
}
