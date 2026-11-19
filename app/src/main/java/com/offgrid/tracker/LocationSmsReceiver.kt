package com.offgrid.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class LocationSmsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PEER_LOCATION_UPDATE = "com.offgrid.tracker.PEER_LOCATION_UPDATE"
        const val EXTRA_PACKET = "packet"
        const val EXTRA_SENDER = "sender"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Çok parçalı SMS ihtimaline karşı aynı göndericinin parçalarını birleştir
        val groupedBySender = LinkedHashMap<String, StringBuilder>()
        for (sms in messages) {
            val sender = sms.originatingAddress?.trim() ?: continue
            val part = sms.messageBody ?: continue
            val sb = groupedBySender.getOrPut(sender) { StringBuilder() }
            if (sb.length + part.length <= CryptoHelper.MAX_PACKET_LENGTH * 2) {
                sb.append(part)
            }
        }

        for ((sender, bodyBuilder) in groupedBySender) {
            val body = bodyBuilder.toString().trim()
            if (body.startsWith(CryptoHelper.PACKET_PREFIX) && body.length <= CryptoHelper.MAX_PACKET_LENGTH) {
                val updateIntent = Intent(ACTION_PEER_LOCATION_UPDATE).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_PACKET, body)
                    putExtra(EXTRA_SENDER, sender)
                }
                context.sendBroadcast(updateIntent)
            }
        }
    }
}
