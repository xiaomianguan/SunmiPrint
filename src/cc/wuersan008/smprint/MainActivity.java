package cc.wuersan008.smprint;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import dalvik.system.DexClassLoader;

/**
 * SunmiPrint - print arbitrary text / images / barcodes / QR on the Sunmi P1
 * built-in 58mm thermal printer (384 dots wide).
 *
 * The printer is driven through the exported system service
 * "woyou.aidlservice.jiuiv5" (no permission required).
 *
 * IMPORTANT: the AIDL interface class is loaded AT RUNTIME from the device's own
 * printer-service APK and invoked reflectively. Binder transaction codes are
 * assigned by AIDL declaration order, and Sunmi reordered that interface between
 * firmware revisions: this P1 runs 3.1.6, which still has updateFirmware() where
 * newer builds have postPrintData() (and it has no printBitmapCustom()). Code
 * compiled against a downloaded AIDL therefore calls the wrong methods. Using the
 * device's own class removes that entire failure mode.
 */
public class MainActivity extends Activity {

    static final String TAG = "SunmiPrint";
    static final String SVC_PKG = "woyou.aidlservice.jiuiv5";
    static final String SVC_ACTION = "woyou.aidlservice.jiuiv5.IWoyouService";
    static final String SVC_APK =
        "/system/app/SunmiPrinterService_P1_3.1.6_release/SunmiPrinterService_P1_3.1.6_release.apk";

    static final int REQ_PICK = 1001;
    static final int PRINT_WIDTH = 384;   // P1 print head width in dots
    static final int CHUNK_H = 255;       // max rows per printBitmap() call

    EditText input;
    TextView status;
    CheckBox cbCenter;
    CheckBox cbDither;

    Class<?> svcIface;
    Class<?> stubCls;
    Class<?> cbIface;
    Object cbProxy;
    Object svc;
    boolean bound;

    // filled from intent extras so the app can be driven from adb / Tasker:
    //   am start -n cc.wuersan008.smprint/.MainActivity -e text "hello"
    //   am start -n cc.wuersan008.smprint/.MainActivity -e path /sdcard/pic.png
    String pendingText;
    String pendingPath;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        loadInterface();
        bindPrinter();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        handleIntent(it);
        if (bound) runPending();
    }

    void handleIntent(Intent it) {
        if (it == null) return;
        if (it.hasExtra("text")) pendingText = it.getStringExtra("text");
        if (it.hasExtra("path")) pendingPath = it.getStringExtra("path");
    }

    void runPending() {
        if (pendingText != null) {
            input.setText(pendingText);
            pendingText = null;
            actPrintText();
        }
        if (pendingPath != null) {
            input.setText(pendingPath);
            pendingPath = null;
            actImagePath();
        }
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            try { unbindService(conn); } catch (Throwable t) { }
            bound = false;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    Button btn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (getResources().getDisplayMetrics().density * 6);
        root.setPadding(p, p, p, p);

        status = new TextView(this);
        status.setText("starting...");
        status.setTextSize(11f);
        root.addView(status);

        input = new EditText(this);
        input.setHint("text to print");
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setTextSize(14f);
        root.addView(input);

        cbCenter = new CheckBox(this);
        cbCenter.setText("center text");
        cbCenter.setChecked(false);
        root.addView(cbCenter);

        cbDither = new CheckBox(this);
        cbDither.setText("dither images (Floyd-Steinberg)");
        cbDither.setChecked(true);
        root.addView(cbDither);

        LinearLayout r1 = row();
        r1.addView(btn("Print text", new View.OnClickListener() {
            public void onClick(View v) { actPrintText(); }
        }));
        r1.addView(btn("Feed", new View.OnClickListener() {
            public void onClick(View v) { actFeed(); }
        }));
        r1.addView(btn("QR", new View.OnClickListener() {
            public void onClick(View v) { actQr(); }
        }));
        root.addView(r1);

        LinearLayout r2 = row();
        r2.addView(btn("Barcode", new View.OnClickListener() {
            public void onClick(View v) { actBarcode(); }
        }));
        r2.addView(btn("Pick image", new View.OnClickListener() {
            public void onClick(View v) { actPickImage(); }
        }));
        r2.addView(btn("Image path", new View.OnClickListener() {
            public void onClick(View v) { actImagePath(); }
        }));
        root.addView(r2);

        LinearLayout r3 = row();
        r3.addView(btn("Test pattern", new View.OnClickListener() {
            public void onClick(View v) { actTestPattern(); }
        }));
        r3.addView(btn("Info", new View.OnClickListener() {
            public void onClick(View v) { actInfo(); }
        }));
        r3.addView(btn("Init", new View.OnClickListener() {
            public void onClick(View v) { actInit(); }
        }));
        root.addView(r3);

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        return sc;
    }

    // ------------------------------------------------- interface / binding

    void loadInterface() {
        try {
            DexClassLoader dcl;
            try {
                dcl = new DexClassLoader(SVC_APK, null, null, getClassLoader());
            } catch (Throwable t) {
                dcl = new DexClassLoader(SVC_APK, getDir("dex", 0).getAbsolutePath(),
                        null, getClassLoader());
            }
            svcIface = dcl.loadClass("woyou.aidlservice.jiuiv5.IWoyouService");
            stubCls = dcl.loadClass("woyou.aidlservice.jiuiv5.IWoyouService$Stub");
            cbIface = dcl.loadClass("woyou.aidlservice.jiuiv5.ICallback");
            makeCallback();
            post("interface loaded from device APK");
        } catch (Throwable t) {
            Log.e(TAG, "loadInterface failed", t);
            post("LOAD FAILED: " + t);
        }
    }

    void makeCallback() {
        // A Binder that silently swallows callbacks: the service gets a valid
        // callback object without us having to implement the device's ICallback.
        final Binder sink = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                return true;
            }
        };
        cbProxy = Proxy.newProxyInstance(cbIface.getClassLoader(),
                new Class<?>[] { cbIface },
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("asBinder".equals(m.getName())) return sink;
                        return null;
                    }
                });
    }

    void bindPrinter() {
        if (svcIface == null) return;
        Intent i = new Intent(SVC_ACTION);
        i.setPackage(SVC_PKG);
        try {
            bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.e(TAG, "bindService failed", t);
            post("bind failed: " + t);
        }
    }

    final ServiceConnection conn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder binder) {
            try {
                // asInterface() is a static method on the generated Stub, not on the interface.
                Method asInterface = stubCls.getMethod("asInterface", IBinder.class);
                svc = asInterface.invoke(null, binder);
                bound = true;
                post("connected to printer service");
                actInfo();
                runPending();
            } catch (Throwable t) {
                Log.e(TAG, "onServiceConnected failed", t);
                post("asInterface failed: " + t);
            }
        }

        public void onServiceDisconnected(ComponentName n) {
            svc = null;
            bound = false;
            post("printer service disconnected");
        }
    };

    // --------------------------------------------------------- raw calling

    Object call(String name, Class<?>[] sig, Object[] args) throws Exception {
        Method m = svcIface.getMethod(name, sig);
        return m.invoke(svc, args);
    }

    String callStr(String name) {
        try {
            Object r = call(name, new Class<?>[] { }, new Object[] { });
            return (r == null) ? "null" : String.valueOf(r);
        } catch (Throwable t) {
            return "n/a";
        }
    }

    void setAlign(int a) throws Exception {
        call("setAlignment", new Class<?>[] { int.class, cbIface },
                new Object[] { Integer.valueOf(a), cbProxy });
    }

    void pText(String s) throws Exception {
        call("printText", new Class<?>[] { String.class, cbIface },
                new Object[] { s, cbProxy });
    }

    void pFeed(int lines) throws Exception {
        call("lineWrap", new Class<?>[] { int.class, cbIface },
                new Object[] { Integer.valueOf(lines), cbProxy });
    }

    void pBitmap(Bitmap bm) throws Exception {
        call("printBitmap", new Class<?>[] { Bitmap.class, cbIface },
                new Object[] { bm, cbProxy });
    }

    void pQr(String data, int size, int level) throws Exception {
        call("printQRCode", new Class<?>[] { String.class, int.class, int.class, cbIface },
                new Object[] { data, Integer.valueOf(size), Integer.valueOf(level), cbProxy });
    }

    void pBarcode(String data, int symbology, int height, int width, int textPos)
            throws Exception {
        call("printBarCode", new Class<?>[] { String.class, int.class, int.class,
                        int.class, int.class, cbIface },
                new Object[] { data, Integer.valueOf(symbology), Integer.valueOf(height),
                        Integer.valueOf(width), Integer.valueOf(textPos), cbProxy });
    }

    void pInit() throws Exception {
        call("printerInit", new Class<?>[] { cbIface }, new Object[] { cbProxy });
    }

    // ------------------------------------------------------------- actions

    String field() {
        return input.getText().toString().trim();
    }

    void actPrintText() {
        final String t = input.getText().toString();
        if (t.trim().length() == 0) { toast("type some text first"); return; }
        bg("printText", new Job() {
            public void run() throws Exception {
                setAlign(cbCenter.isChecked() ? 1 : 0);
                pText(t);
                pFeed(4);
                post("printed " + t.length() + " chars");
            }
        });
    }

    void actFeed() {
        bg("feed", new Job() {
            public void run() throws Exception {
                pFeed(3);
                post("fed 3 lines");
            }
        });
    }

    void actQr() {
        final String t = field().length() > 0 ? field() : "https://sunmi.com";
        bg("qr", new Job() {
            public void run() throws Exception {
                setAlign(1);
                pQr(t, 8, 2);
                pFeed(4);
                post("printed QR: " + t);
            }
        });
    }

    void actBarcode() {
        final String t = field().length() > 0 ? field() : "123456789012";
        bg("barcode", new Job() {
            public void run() throws Exception {
                setAlign(1);
                pBarcode(t, 8, 100, 3, 2);   // 8 = CODE128, height 100, width 3, text below
                pFeed(4);
                post("printed barcode: " + t);
            }
        });
    }

    void actInit() {
        bg("init", new Job() {
            public void run() throws Exception {
                pInit();
                post("printer initialised");
            }
        });
    }

    void actInfo() {
        bg("info", new Job() {
            public void run() throws Exception {
                StringBuilder sb = new StringBuilder();
                sb.append("svc ").append(callStr("getServiceVersion"));
                sb.append(" | fw ").append(callStr("getPrinterVersion"));
                sb.append(" | model ").append(callStr("getPrinterModal"));
                sb.append(" | sn ").append(callStr("getPrinterSerialNo"));
                sb.append(" | state ").append(callStr("updatePrinterState"));
                sb.append(" | head ").append(callStr("getPrinterFactory"));
                post(sb.toString());
            }
        });
    }

    void actTestPattern() {
        bg("test pattern", new Job() {
            public void run() throws Exception {
                printImage(testPattern());
            }
        });
    }

    void actPickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            toast("no image picker: " + t.getClass().getSimpleName());
        }
    }

    void actImagePath() {
        final String path = field();
        if (path.length() == 0) { toast("put an image path in the text box"); return; }
        bg("image path", new Job() {
            public void run() throws Exception {
                String p = path.startsWith("file://") ? path.substring(7) : path;
                if (!new File(p).exists()) throw new Exception("no such file: " + p);
                printImage(BitmapFactory.decodeFile(p));
            }
        });
    }

    // ------------------------------------------------------ image printing

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        bg("image", new Job() {
            public void run() throws Exception {
                InputStream in = getContentResolver().openInputStream(uri);
                Bitmap bm = BitmapFactory.decodeStream(in);
                if (in != null) in.close();
                printImage(bm);
            }
        });
    }

    void printImage(Bitmap src) throws Exception {
        if (src == null) throw new Exception("could not decode image");
        Bitmap mono = prepare(src, cbDither.isChecked());
        setAlign(0);
        int y = 0;
        int h = mono.getHeight();
        while (y < h) {
            int ch = Math.min(CHUNK_H, h - y);
            Bitmap part = Bitmap.createBitmap(mono, 0, y, mono.getWidth(), ch);
            pBitmap(part);
            part.recycle();
            y += ch;
        }
        pFeed(4);
        post("printed image " + mono.getWidth() + "x" + mono.getHeight()
                + (cbDither.isChecked() ? " dithered" : " threshold"));
    }

    /** Scale to 384 dots wide and reduce to the 1-bit black/white the head can print. */
    Bitmap prepare(Bitmap src, boolean dither) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw < 1 || sh < 1) return src;
        int w = PRINT_WIDTH;
        int h = Math.max(1, (int) Math.round(sh * (PRINT_WIDTH / (double) sw)));
        Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);

        int[] px = new int[w * h];
        scaled.getPixels(px, 0, w, 0, 0, w, h);

        float[] gray = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xff;
            int g = (c >> 8) & 0xff;
            int b = c & 0xff;
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b;
        }

        if (dither) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float old = gray[i];
                    float nv = (old < 128f) ? 0f : 255f;
                    gray[i] = nv;
                    float err = old - nv;
                    if (x + 1 < w) gray[i + 1] += err * 7f / 16f;
                    if (y + 1 < h) {
                        if (x > 0) gray[i + w - 1] += err * 3f / 16f;
                        gray[i + w] += err * 5f / 16f;
                        if (x + 1 < w) gray[i + w + 1] += err * 1f / 16f;
                    }
                }
            }
        }

        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            out[i] = (gray[i] < 128f) ? Color.BLACK : Color.WHITE;
        }
        Bitmap mono = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        mono.setPixels(out, 0, w, 0, 0, w, h);
        return mono;
    }

    Bitmap testPattern() {
        int w = PRINT_WIDTH;
        int h = 200;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(Color.WHITE);
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        for (int x = 0; x < w; x += 16) {
            c.drawRect(x, 0, x + 8, 40, p);          // 8/16 dot bars
        }
        for (int i = 0; i < 16; i++) {               // grayscale ramp, dithered later
            int g = 255 - i * 16;
            p.setColor(Color.rgb(g, g, g));
            c.drawRect(i * (w / 16), 45, (i + 1) * (w / 16), 95, p);
        }
        p.setColor(Color.BLACK);
        p.setTextSize(22f);
        c.drawText("SUNMI P1 PRINTER TEST", 6, 130, p);
        p.setTextSize(16f);
        c.drawText("384 dots  |  " + android.os.Build.MODEL, 6, 160, p);
        return bm;
    }

    // -------------------------------------------------------------- status

    interface Job { void run() throws Exception; }

    void bg(final String label, final Job job) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    job.run();
                } catch (final Throwable t) {
                    Log.e(TAG, label + " failed", t);
                    post(label + " FAILED: " + t);
                    toast(label + " failed: " + t.getClass().getSimpleName());
                }
            }
        }).start();
    }

    void post(final String s) {
        Log.i(TAG, s);
        runOnUiThread(new Runnable() {
            public void run() {
                if (status != null) status.setText(s);
            }
        });
    }

    void toast(final String s) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
