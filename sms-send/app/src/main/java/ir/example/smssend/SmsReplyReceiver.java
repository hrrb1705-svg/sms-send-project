package ir.example.smssend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONObject;

public class SmsReplyReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReplyReceiver";
    private static final String REPLY_STORE = "reply_store";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            return;
        }
        Object[] pdus = (Object[]) intent.getExtras().get("pdus");
        if (pdus == null || pdus.length == 0) {
            return;
        }
        StringBuilder body = new StringBuilder();
        String address = null;
        String format = intent.getStringExtra("format");
        for (Object pdu : pdus) {
            SmsMessage message;
            if (format != null) {
                message = SmsMessage.createFromPdu((byte[]) pdu, format);
            } else {
                message = SmsMessage.createFromPdu((byte[]) pdu);
            }
            if (message == null) {
                continue;
            }
            if (address == null) {
                address = message.getDisplayOriginatingAddress();
            }
            body.append(message.getMessageBody() == null ? "" : message.getMessageBody());
        }
        if (address == null || address.trim().isEmpty() || body.length() == 0) {
            return;
        }
        String normalizedAddress = MainActivity.normalizePhone(address);
        if (normalizedAddress.isEmpty()) {
            normalizedAddress = address.trim();
        }
        SharedPreferences prefs = context.getSharedPreferences(REPLY_STORE, Context.MODE_PRIVATE);
        try {
            JSONObject store = new JSONObject(prefs.getString("data", "{}"));
            String previous = store.optString(normalizedAddress, "");
            String current = body.toString().trim();
            if (previous.isEmpty()) {
                store.put(normalizedAddress, current);
            } else if (!previous.contains(current)) {
                store.put(normalizedAddress, previous + "\n" + current);
            }
            prefs.edit().putString("data", store.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to store reply", e);
        }
    }
}
