package com.fadcam.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class ShortcutPinResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String label = intent != null ? intent.getStringExtra("label") : null;
        if(label == null) label = "Shortcut";
        Toast.makeText(context, label + " added to Home", Toast.LENGTH_SHORT).show();
    }
}
