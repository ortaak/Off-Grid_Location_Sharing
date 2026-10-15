package com.offgrid.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class LocationSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val body = sms.messageBody ?: continue
                val sender = sms.originatingAddress ?: "Bilinmeyen"
                if (body.startsWith(CryptoHelper.PACKET_PREFIX)) {
                    val updateIntent = Intent("com.offgrid.tracker.PEER_LOCATION_UPDATE").apply {
                        setPackage(context.packageName)
                        putExtra("packet", body)
                        putExtra("sender", sender)
                    }
                    context.sendBroadcast(updateIntent)
                }
            }
        }
    }
}
