package io.github.wellingtonsaam.mflasherlib;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;

/**
 * Spike: find an FM-1 in download mode on USB, get permission, and read its whole flash into a
 * backup with M-Flasher's core. Nothing is written to the unit.
 */
public class MainActivity extends Activity {
    private static final String TAG = "MFLR";
    private static final String ACTION_PERMISSION = "io.github.wellingtonsaam.mflasherlib.USB_PERMISSION";
    private static final int VID = 0x4C4A;
    private static final int PID = 0x8057;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private UsbManager usb;
    private TextView out;
    private Button button;
    private ScrollView scroll;
    private volatile boolean running;

    private final BroadcastReceiver permission = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_PERMISSION.equals(intent.getAction())) return;
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) {
                say("Permissão USB concedida");
                start(d);
            } else {
                say("Permissão USB negada");
            }
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            String lines = Native.takeLog();
            if (!lines.isEmpty()) say(lines);
            if (running) {
                button.setText("Lendo… " + Native.progress() + "%");
                ui.postDelayed(this, 300);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = getSystemService(UsbManager.class);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        button = new Button(this);
        button.setText("Ler FM-1 (backup, só leitura)");
        button.setOnClickListener(v -> onRead());
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12);
        out.setTextIsSelectable(true);
        scroll = new ScrollView(this);
        scroll.addView(out);
        root.addView(button);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permission, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(permission, f);
        }
        say("M-Flasher Library: spike Android (" + Build.MODEL + ", Android " + Build.VERSION.RELEASE + ")");
        say("Ponha o FM-1 em modo download (PLAY ao ligar → DOWNLOAD MODE, ou REC ao ligar) e ligue-o ao celular.");
        describe();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        describe();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(permission);
        super.onDestroy();
    }

    private void say(String s) {
        Log.i(TAG, s);
        out.append(s + "\n");
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private UsbDevice find() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        }
        return null;
    }

    private void describe() {
        StringBuilder s = new StringBuilder("USB: ");
        if (usb.getDeviceList().isEmpty()) s.append("nenhum dispositivo");
        for (UsbDevice d : usb.getDeviceList().values()) {
            s.append(String.format("%04X:%04X %s; ", d.getVendorId(), d.getProductId(), d.getProductName()));
        }
        say(s.toString());
    }

    private void onRead() {
        if (running) return;
        describe();
        UsbDevice d = find();
        if (d == null) {
            say("Nenhum FM-1 em modo download (USB 4C4A:8057) encontrado.");
            return;
        }
        if (!usb.hasPermission(d)) {
            Intent i = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
            say("Pedindo permissão USB…");
            return;
        }
        start(d);
    }

    private void start(UsbDevice d) {
        UsbInterface intf = null;
        UsbEndpoint in = null, outEp = null;
        for (int i = 0; i < d.getInterfaceCount() && intf == null; i++) {
            UsbInterface c = d.getInterface(i);
            if (c.getInterfaceClass() != UsbConstants.USB_CLASS_MASS_STORAGE) continue;
            UsbEndpoint ei = null, eo = null;
            for (int e = 0; e < c.getEndpointCount(); e++) {
                UsbEndpoint ep = c.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) ei = ep; else eo = ep;
            }
            if (ei != null && eo != null) {
                intf = c;
                in = ei;
                outEp = eo;
            }
        }
        if (intf == null) {
            say("O dispositivo não tem a interface de mass storage esperada.");
            return;
        }
        UsbDeviceConnection conn = usb.openDevice(d);
        if (conn == null) {
            say("Não consegui abrir o dispositivo USB.");
            return;
        }
        if (!conn.claimInterface(intf, true)) {
            conn.close();
            say("Não consegui assumir a interface USB (claimInterface).");
            return;
        }
        final UsbInterface fIntf = intf;
        final UsbEndpoint fIn = in, fOut = outEp;
        final File dir = new File(getExternalFilesDir(null), "backups");
        say(String.format("Interface %d, endpoints IN 0x%02X (%d B) / OUT 0x%02X, fd %d",
                intf.getId(), in.getAddress(), in.getMaxPacketSize(), outEp.getAddress(), conn.getFileDescriptor()));
        running = true;
        button.setText("Lendo…");
        ui.post(poll);
        new Thread(() -> {
            String r = Native.backup(conn.getFileDescriptor(), fIntf.getId(), fIn.getAddress(), fOut.getAddress(),
                    fIn.getMaxPacketSize(), dir.getAbsolutePath());
            conn.releaseInterface(fIntf);
            conn.close();
            ui.post(() -> {
                running = false;
                String lines = Native.takeLog();
                if (!lines.isEmpty()) say(lines);
                say("----");
                say(r);
                button.setText("Ler FM-1 (backup, só leitura)");
            });
        }, "mflr-backup").start();
    }
}
