package com.echolink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * 短信到达广播接收器（主检测通道，比 ContentObserver 可靠）：
 * 系统收到短信时直接广播 SMS_RECEIVED，无需轮询收件箱，
 * 各 Android 版本行为一致。开关 + 权限在 SmsCodeWatcher 内统一判定。
 */
class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // 拼接所有分片的完整短信正文
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val bodyBuilder = StringBuilder()
        var address = ""
        for (msg in messages) {
            if (address.isBlank()) address = msg.originatingAddress ?: ""
            bodyBuilder.append(msg.displayMessageBody ?: "")
        }
        val body = bodyBuilder.toString()
        if (body.isBlank()) return

        Log.i(TAG, "SMS received from $address (${body.length} chars)")

        // 交给 SmsCodeWatcher 统一处理（开关 + 权限判定 + 提取验证码 + 复制 + 上报服务器）
        SmsCodeWatcher.processReceivedSms(context.applicationContext, body, address)
    }
}
