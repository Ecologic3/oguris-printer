package app.oguri.printer;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    private static final int REQ_PICK_IMAGE = 7;
    private static final int BG = Color.rgb(0x17, 0x13, 0x10);
    private static final int CARD = Color.rgb(0x26, 0x20, 0x1B);
    private static final int TEXT = Color.rgb(0xFF, 0xF7, 0xED);
    private static final int MUTED = Color.rgb(0xB8, 0xA9, 0x9A);
    private static final int ACCENT = Color.rgb(0xF5, 0xA8, 0x3D);
    private static final int CHIPBG = Color.rgb(0x3A, 0x31, 0x29);
    private static final int DARKTEXT = Color.rgb(0x24, 0x15, 0x03);

    private static final String PREFS = "printer";
    private static final String KEY_NAME = "last_name";
    private static final String KEY_MAC = "last_mac";
    private static final String KEY_MAC_CLASSIC = "last_mac_classic";
    private static final String KEY_MAC_BLE = "last_mac_ble";
    private static final String KEY_TRANSPORT = "last_transport";

    private PrinterConnection connection;
    private ExecutorService io;
    private Handler mainHandler;

    private TextView printerNameView;
    private TextView statusView;
    private TextView detailsView;
    private Button connectBtn;
    private Button scanBtn;
    private LinearLayout deviceList;

    private final List<PrinterDevices.Device> devices = new ArrayList<PrinterDevices.Device>();
    private boolean bleScanning;
    private boolean classicScanning;
    private boolean receiverRegistered;
    private BluetoothLeScanner leScanner;

    // Fresh-start selection is Dark (2); the protocol-level default
    // (PrinterProtocol.DEFAULT_DENSITY, Medium) is not the app default.
    private int density = 2;
    private Button[] densityButtons = new Button[3];
    private int copies = 1;
    private TextView copiesView;

    private Button textTabBtn;
    private Button imageTabBtn;
    private LinearLayout textSection;
    private LinearLayout imageSection;

    private EditText textEdit;
    private SeekBar textSizeBar;
    private TextView textSizeLabel;
    private Button[] alignButtons = new Button[3];
    private int textAlign = TextLayout.ALIGN_LEFT;
    private Button[] fontButtons = new Button[3];
    private int textFont = 0; // 0 Sans, 1 Serif, 2 Monospace
    private CheckBox boldCheck;
    private CheckBox invertCheck;
    private SeekBar spacingBar;
    private TextView spacingLabel;
    private float spacingMult = 1.0f;
    private ImageView textPreview;
    private boolean[] textBlack;
    private int textBlackW;
    private int textBlackH;

    private CheckBox ditherCheck;
    private ImageView imagePreview;
    private int[] imageGray;
    private int imageW;
    private int imageH;
    private boolean[] imageBlack;

    private String statusSummary = "";
    private int batteryRaw = -1;
    private String modelInfo = "";

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- lifecycle ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        io = Executors.newSingleThreadExecutor();
        connection = new PrinterConnection(new PrinterConnection.Events() {
            @Override public void onUnsolicitedError(final String description) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        statusSummary = description;
                        updatePrinterCard();
                        Toast.makeText(MainActivity.this,
                                "Printer: " + description, Toast.LENGTH_LONG).show();
                    }
                });
            }
            @Override public void onDisconnected(String transportName) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        statusSummary = "Disconnected";
                        updatePrinterCard();
                    }
                });
            }
        });
        buildUi();
        addLastUsedToList();
        updatePrinterCard();
    }

    @Override
    protected void onDestroy() {
        stopScan();
        if (receiverRegistered) {
            try { unregisterReceiver(classicReceiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        if (connection != null) connection.disconnect();
        if (io != null) io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Toast.makeText(this, "Permissions updated, tap Scan again", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            InputStream in = getContentResolver().openInputStream(data.getData());
            if (in == null) throw new Exception("no stream");
            Bitmap bmp = BitmapFactory.decodeStream(in);
            in.close();
            if (bmp == null) throw new Exception("not an image");
            loadImage(bmp);
        } catch (Exception e) {
            Toast.makeText(this, "Could not load that image", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- UI construction ----------

    private GradientDrawable cardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(dp(16));
        return d;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(cardBg());
        l.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(14));
        l.setLayoutParams(lp);
        return l;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        return b;
    }

    /** Warm-accent tint for seek bars, replacing the platform default. */
    private void styleSeekBar(SeekBar bar) {
        ColorStateList tint = ColorStateList.valueOf(ACCENT);
        bar.setProgressTintList(tint);
        bar.setThumbTintList(tint);
    }

    /** Warm-accent tint for check boxes, replacing the platform default. */
    private void styleCheckBox(CheckBox box) {
        box.setButtonTintList(ColorStateList.valueOf(ACCENT));
    }

    /** Segmented chip row styling, shared by density/alignment/font selectors. */
    private void styleChip(Button b, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(selected ? ACCENT : CHIPBG);
        b.setBackground(bg);
        b.setTextColor(selected ? DARKTEXT : TEXT);
    }

    /** One row of equally weighted chip buttons wired to the given listener factory. */
    private LinearLayout chipRow(String[] labels, Button[] out, int selected,
                                 final java.util.function.IntConsumer onPick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(rowLp);
        for (int i = 0; i < labels.length; i++) {
            final int value = i;
            Button b = button(labels[i]);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) blp.setMargins(dp(6), 0, 0, 0);
            b.setLayoutParams(blp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onPick.accept(value); }
            });
            out[i] = b;
            row.addView(b);
        }
        return row;
    }

    private void rerenderTextIfAny() {
        if (textEdit != null && textEdit.getText().toString().trim().length() > 0) {
            renderTextPreview();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(22));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.setMargins(0, 0, 0, dp(16));
        header.setLayoutParams(headerLp);
        TextView title = text("Oguri's Printer", 30, TEXT, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        ImageView headerIcon = new ImageView(this);
        try {
            headerIcon.setImageDrawable(
                    getPackageManager().getApplicationIcon(getPackageName()));
        } catch (Exception ignored) { }
        headerIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(headerIcon);
        root.addView(header);

        // Printer card
        LinearLayout printerCard = card();
        printerNameView = text("Not connected", 20, TEXT, true);
        printerCard.addView(printerNameView);
        statusView = text("", 15, ACCENT, false);
        printerCard.addView(statusView);
        detailsView = text("", 13, MUTED, false);
        printerCard.addView(detailsView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, dp(12), 0, 0);
        row1.setLayoutParams(rowLp);
        scanBtn = button("Scan");
        scanBtn.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (bleScanning || classicScanning) stopScan(); else startScan();
            }
        });
        row1.addView(scanBtn);
        connectBtn = button("Disconnect");
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cbLp.setMargins(dp(8), 0, 0, 0);
        connectBtn.setLayoutParams(cbLp);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                io.execute(new Runnable() {
                    @Override public void run() {
                        connection.disconnect();
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                statusSummary = "";
                                batteryRaw = -1;
                                modelInfo = "";
                                updatePrinterCard();
                            }
                        });
                    }
                });
            }
        });
        row1.addView(connectBtn);
        printerCard.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setLayoutParams(rowLp);
        Button refreshBtn = button("Refresh status");
        refreshBtn.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshInfoAsync(); }
        });
        row2.addView(refreshBtn);
        Button feedBtn = button("Feed");
        LinearLayout.LayoutParams fbLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fbLp.setMargins(dp(8), 0, 0, 0);
        feedBtn.setLayoutParams(fbLp);
        feedBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!connection.isConnected()) {
                    Toast.makeText(MainActivity.this, "Connect a printer first",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                io.execute(new Runnable() {
                    @Override public void run() {
                        final boolean ok = connection.feed();
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this,
                                        ok ? "Fed paper" : "Feed failed",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            }
        });
        row2.addView(feedBtn);
        printerCard.addView(row2);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlLp.setMargins(0, dp(10), 0, 0);
        deviceList.setLayoutParams(dlLp);
        printerCard.addView(deviceList);
        root.addView(printerCard);

        // Settings card: density + copies
        LinearLayout settings = card();
        settings.addView(text("Density", 16, TEXT, true));
        LinearLayout densRow = new LinearLayout(this);
        densRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams densLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        densLp.setMargins(0, dp(8), 0, 0);
        densRow.setLayoutParams(densLp);
        String[] densNames = {"Light", "Medium", "Dark"};
        for (int i = 0; i < 3; i++) {
            final int value = i;
            Button b = button(densNames[i]);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) blp.setMargins(dp(6), 0, 0, 0);
            b.setLayoutParams(blp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    density = value;
                    refreshDensityButtons();
                }
            });
            densityButtons[i] = b;
            densRow.addView(b);
        }
        settings.addView(densRow);
        refreshDensityButtons();

        LinearLayout copiesRow = new LinearLayout(this);
        copiesRow.setOrientation(LinearLayout.HORIZONTAL);
        copiesRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams copLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copLp.setMargins(0, dp(14), 0, 0);
        copiesRow.setLayoutParams(copLp);
        TextView copiesLabel = text("Copies", 16, TEXT, true);
        copiesLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        copiesRow.addView(copiesLabel);
        Button minus = button("-");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (copies > 1) copies--;
                copiesView.setText(String.valueOf(copies));
            }
        });
        copiesRow.addView(minus);
        copiesView = text("1", 18, TEXT, true);
        copiesView.setGravity(Gravity.CENTER);
        copiesView.setLayoutParams(new LinearLayout.LayoutParams(dp(44),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        copiesRow.addView(copiesView);
        Button plus = button("+");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (copies < 3) copies++;
                copiesView.setText(String.valueOf(copies));
            }
        });
        copiesRow.addView(plus);
        settings.addView(copiesRow);
        root.addView(settings);

        // Tabs
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        textTabBtn = button("Text");
        textTabBtn.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        textTabBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(true); }
        });
        tabRow.addView(textTabBtn);
        imageTabBtn = button("Image");
        LinearLayout.LayoutParams itLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itLp.setMargins(dp(8), 0, 0, 0);
        imageTabBtn.setLayoutParams(itLp);
        imageTabBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(false); }
        });
        tabRow.addView(imageTabBtn);
        root.addView(tabRow);

        // Text section
        textSection = card();
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsLp.setMargins(0, dp(14), 0, 0);
        textSection.setLayoutParams(tsLp);
        textEdit = new EditText(this);
        textEdit.setHint("Type or paste text to print");
        textEdit.setTextColor(TEXT);
        textEdit.setHintTextColor(MUTED);
        textEdit.setMinLines(4);
        textEdit.setGravity(Gravity.TOP);
        textSection.addView(textEdit);
        textSizeLabel = text("Text size: 44", 14, MUTED, false);
        LinearLayout.LayoutParams optLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        optLabelLp.setMargins(0, dp(12), 0, 0);
        textSizeLabel.setLayoutParams(optLabelLp);
        textSection.addView(textSizeLabel);
        textSizeBar = new SeekBar(this);
        textSizeBar.setMax(104); // 16 + 104 = 120 px
        textSizeBar.setProgress(28); // 16 + 28 = 44
        styleSeekBar(textSizeBar);
        textSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                textSizeLabel.setText("Text size: " + (16 + progress));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { rerenderTextIfAny(); }
        });
        textSection.addView(textSizeBar);

        TextView alignLabel = text("Alignment", 14, MUTED, false);
        alignLabel.setLayoutParams(optLabelLp);
        textSection.addView(alignLabel);
        textSection.addView(chipRow(new String[]{"Left", "Centre", "Right"},
                alignButtons, textAlign, new java.util.function.IntConsumer() {
                    @Override public void accept(int value) {
                        textAlign = value;
                        refreshAlignButtons();
                        rerenderTextIfAny();
                    }
                }));
        refreshAlignButtons();

        TextView fontLabel = text("Font", 14, MUTED, false);
        fontLabel.setLayoutParams(optLabelLp);
        textSection.addView(fontLabel);
        textSection.addView(chipRow(new String[]{"Sans", "Serif", "Mono"},
                fontButtons, textFont, new java.util.function.IntConsumer() {
                    @Override public void accept(int value) {
                        textFont = value;
                        refreshFontButtons();
                        rerenderTextIfAny();
                    }
                }));
        refreshFontButtons();

        // Two equal columns, top-aligned, so the Bold and Invert boxes
        // start at exactly the same height. Labels never wrap; the
        // Invert explanation is a small caption under its label.
        LinearLayout boldInvertRow = new LinearLayout(this);
        boldInvertRow.setOrientation(LinearLayout.HORIZONTAL);
        boldInvertRow.setGravity(Gravity.TOP);
        boldInvertRow.setLayoutParams(optLabelLp);

        LinearLayout boldCol = new LinearLayout(this);
        boldCol.setOrientation(LinearLayout.VERTICAL);
        boldCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boldCheck = new CheckBox(this);
        boldCheck.setText("Bold");
        boldCheck.setSingleLine(true);
        boldCheck.setTextColor(TEXT);
        styleCheckBox(boldCheck);
        boldCheck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { rerenderTextIfAny(); }
        });
        boldCol.addView(boldCheck);
        boldInvertRow.addView(boldCol);

        LinearLayout invertCol = new LinearLayout(this);
        invertCol.setOrientation(LinearLayout.VERTICAL);
        invertCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        invertCheck = new CheckBox(this);
        invertCheck.setText("Invert");
        invertCheck.setSingleLine(true);
        invertCheck.setTextColor(TEXT);
        styleCheckBox(invertCheck);
        invertCheck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { rerenderTextIfAny(); }
        });
        invertCol.addView(invertCheck);
        TextView invertCaption = text("white on black", 12, MUTED, false);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.setMargins(dp(34), 0, 0, 0);
        invertCaption.setLayoutParams(capLp);
        invertCol.addView(invertCaption);
        boldInvertRow.addView(invertCol);
        textSection.addView(boldInvertRow);

        spacingLabel = text("Line spacing: 1.00x", 14, MUTED, false);
        spacingLabel.setLayoutParams(optLabelLp);
        textSection.addView(spacingLabel);
        spacingBar = new SeekBar(this);
        spacingBar.setMax(20);
        spacingBar.setProgress(0); // fresh-start default: 1.00x, thumb at minimum
        styleSeekBar(spacingBar);
        spacingBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                spacingMult = 1.0f + progress * 0.05f;
                spacingLabel.setText("Line spacing: "
                        + String.format(java.util.Locale.US, "%.2fx", spacingMult));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { rerenderTextIfAny(); }
        });
        textSection.addView(spacingBar);
        textPreview = previewImage();
        textSection.addView(textPreview);
        LinearLayout textBtns = new LinearLayout(this);
        textBtns.setOrientation(LinearLayout.HORIZONTAL);
        textBtns.setLayoutParams(rowLp);
        Button previewBtn = button("Preview");
        previewBtn.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        previewBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { renderTextPreview(); }
        });
        textBtns.addView(previewBtn);
        Button printTextBtn = button("Print");
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ptLp.setMargins(dp(8), 0, 0, 0);
        printTextBtn.setLayoutParams(ptLp);
        printTextBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (renderTextPreview()) {
                    printCurrent(textBlack, textBlackW, textBlackH);
                }
            }
        });
        textBtns.addView(printTextBtn);
        textSection.addView(textBtns);
        root.addView(textSection);

        // Image section
        imageSection = card();
        imageSection.setLayoutParams(tsLp);
        Button pickBtn = button("Pick an image");
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQ_PICK_IMAGE);
            }
        });
        imageSection.addView(pickBtn);
        ditherCheck = new CheckBox(this);
        ditherCheck.setText("Dither (better photos). Off = plain black/white threshold");
        ditherCheck.setTextColor(TEXT);
        styleCheckBox(ditherCheck);
        ditherCheck.setChecked(true);
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dcLp.setMargins(0, dp(8), 0, 0);
        ditherCheck.setLayoutParams(dcLp);
        ditherCheck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { processImage(); }
        });
        imageSection.addView(ditherCheck);
        imagePreview = previewImage();
        imageSection.addView(imagePreview);
        Button printImageBtn = button("Print");
        LinearLayout.LayoutParams piLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        piLp.setMargins(0, dp(12), 0, 0);
        printImageBtn.setLayoutParams(piLp);
        printImageBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (imageBlack == null) {
                    Toast.makeText(MainActivity.this, "Pick an image first",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                printCurrent(imageBlack, imageW, imageH);
            }
        });
        imageSection.addView(printImageBtn);
        root.addView(imageSection);

        showTab(true);
        setContentView(scroll);
    }

    private ImageView previewImage() {
        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(Color.WHITE);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        iv.setLayoutParams(lp);
        iv.setVisibility(View.GONE);
        return iv;
    }

    private void showTab(boolean textTab) {
        textSection.setVisibility(textTab ? View.VISIBLE : View.GONE);
        imageSection.setVisibility(textTab ? View.GONE : View.VISIBLE);
        styleTab(textTabBtn, textTab);
        styleTab(imageTabBtn, !textTab);
    }

    private void styleTab(Button b, boolean selected) {
        styleChip(b, selected);
    }

    private void refreshDensityButtons() {
        for (int i = 0; i < 3; i++) styleChip(densityButtons[i], i == density);
    }

    private void refreshAlignButtons() {
        for (int i = 0; i < 3; i++) styleChip(alignButtons[i], i == textAlign);
    }

    private void refreshFontButtons() {
        for (int i = 0; i < 3; i++) styleChip(fontButtons[i], i == textFont);
    }

    // ---------- printer card / device info ----------

    private void updatePrinterCard() {
        if (printerNameView == null) return;
        connectBtn.setVisibility(connection.isConnected() ? View.VISIBLE : View.GONE);
        if (connection.isConnected()) {
            String name = connection.deviceName();
            printerNameView.setText(name != null && name.length() > 0
                    ? name : connection.deviceMac());
            statusView.setText(statusSummary.length() > 0 ? statusSummary : "Connected");
            StringBuilder sb = new StringBuilder();
            sb.append("Via ").append(connection.transportName());
            if (batteryRaw >= 0) sb.append("   Battery: ").append(batteryRaw);
            if (modelInfo.length() > 0) sb.append("\n").append(modelInfo);
            detailsView.setText(sb.toString());
        } else {
            printerNameView.setText("Not connected");
            statusView.setText(statusSummary.length() > 0 ? statusSummary
                    : "Scan, then tap your printer");
            detailsView.setText("");
        }
        rebuildDeviceList();
    }

    private void refreshInfoAsync() {
        if (!connection.isConnected()) {
            Toast.makeText(this, "Connect a printer first", Toast.LENGTH_SHORT).show();
            return;
        }
        io.execute(new Runnable() {
            @Override public void run() { refreshInfoOnIoThread(); }
        });
    }

    /** Runs on the IO thread. */
    private void refreshInfoOnIoThread() {
        PrinterStatus st = connection.queryStatus();
        int bat = connection.queryBattery();
        String model = connection.queryModel();
        String version = connection.queryVersion();
        String serial = connection.querySerial();
        if (st != null) statusSummary = st.summary();
        batteryRaw = bat;
        StringBuilder sb = new StringBuilder();
        if (model != null) sb.append(model);
        if (version != null) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("FW ").append(version);
        }
        if (serial != null) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("SN ").append(serial);
        }
        modelInfo = sb.toString();
        runOnUiThread(new Runnable() {
            @Override public void run() { updatePrinterCard(); }
        });
    }

    // ---------- discovery ----------

    private void addLastUsedToList() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String name = p.getString(KEY_NAME, null);
        if (name == null) return;
        String classicMac = p.getString(KEY_MAC_CLASSIC, null);
        String bleMac = p.getString(KEY_MAC_BLE, null);
        if (classicMac == null && bleMac == null) {
            // Prefs written by 1.0: a single address plus the transport it used.
            String mac = p.getString(KEY_MAC, null);
            if (mac == null) return;
            if (PrinterConnection.TRANSPORT_BLE.equals(p.getString(KEY_TRANSPORT, null))) {
                bleMac = mac;
            } else {
                classicMac = mac;
            }
        }
        PrinterDevices.Device d = new PrinterDevices.Device(name);
        d.classicMac = classicMac;
        d.bleMac = bleMac;
        d.tags.add(PrinterDevices.TAG_LAST_USED);
        devices.add(d);
        rebuildDeviceList();
    }

    private static boolean isCompatibleName(String name) {
        return name != null && (name.startsWith("PPS1_")
                || name.startsWith("PIRANHA-5510")
                || name.startsWith("WPNT-01_"));
    }

    private void addDevice(String name, String mac, String via) {
        if (!isCompatibleName(name) || mac == null) return;
        int idx = PrinterDevices.findMatch(devices, name, mac);
        if (idx >= 0) {
            devices.get(idx).addSighting(name, mac, via);
        } else {
            PrinterDevices.Device d = new PrinterDevices.Device(name);
            d.addSighting(name, mac, via);
            devices.add(d);
        }
        rebuildDeviceList();
    }

    private void rebuildDeviceList() {
        if (deviceList == null) return;
        deviceList.removeAllViews();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String lastMac = p.getString(KEY_MAC, null);
        if (devices.isEmpty()) {
            deviceList.addView(text("No printers found yet. Turn the printer on and tap Scan.",
                    13, MUTED, false));
            return;
        }
        for (int i = 0; i < devices.size(); i++) {
            final PrinterDevices.Device r = devices.get(i);
            boolean isLast = lastMac != null && (lastMac.equalsIgnoreCase(
                    r.classicMac == null ? "" : r.classicMac)
                    || lastMac.equalsIgnoreCase(r.bleMac == null ? "" : r.bleMac));
            String via = r.viaDisplay();
            TextView row = text(r.name + "\n" + r.primaryMac()
                    + (via.length() > 0 ? "  ·  " + via : "")
                    + (isLast && !r.hasTag(PrinterDevices.TAG_LAST_USED)
                            ? "  ·  Last used" : ""), 15, TEXT, false);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(CHIPBG);
            row.setBackground(bg);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(6), 0, 0);
            row.setLayoutParams(lp);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { connectTo(r); }
            });
            deviceList.addView(row);
        }
    }

    private void startScan() {
        if (!ensurePermissions()) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "This phone has no Bluetooth", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show();
            return;
        }
        // Bonded printers show up straight away.
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    addDevice(safeName(d), safeMac(d), "Bonded");
                }
            }
        } catch (Exception ignored) { }

        // BLE scan.
        try {
            leScanner = adapter.getBluetoothLeScanner();
            if (leScanner != null && hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                leScanner.startScan(leScanCallback);
                bleScanning = true;
            }
        } catch (Exception e) {
            bleScanning = false;
        }

        // Classic discovery.
        try {
            if (!receiverRegistered) {
                IntentFilter f = new IntentFilter();
                f.addAction(BluetoothDevice.ACTION_FOUND);
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                registerReceiver(classicReceiver, f);
                receiverRegistered = true;
            }
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            classicScanning = adapter.startDiscovery();
        } catch (Exception e) {
            classicScanning = false;
        }

        scanBtn.setText("Stop scan");
        Toast.makeText(this, "Scanning for PPS1 printers", Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() { stopScan(); }
        }, 15000);
    }

    private void stopScan() {
        if (bleScanning && leScanner != null) {
            try { leScanner.stopScan(leScanCallback); } catch (Exception ignored) { }
        }
        bleScanning = false;
        if (classicScanning) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                try { adapter.cancelDiscovery(); } catch (Exception ignored) { }
            }
        }
        classicScanning = false;
        if (scanBtn != null) scanBtn.setText("Scan");
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = null;
            if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null) name = safeName(d);
            addDevice(name, safeMac(d), "BLE");
        }
    };

    private final BroadcastReceiver classicReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null) addDevice(safeName(d), safeMac(d), "Classic");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                classicScanning = false;
                if (!bleScanning && scanBtn != null) scanBtn.setText("Scan");
            }
        }
    };

    private String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch (Exception e) { return null; }
    }

    private String safeMac(BluetoothDevice d) {
        try { return d.getAddress(); } catch (Exception e) { return null; }
    }

    // ---------- connect ----------

    private void connectTo(final PrinterDevices.Device r) {
        if (!ensurePermissions()) return;
        stopScan();
        Toast.makeText(this, "Connecting to " + r.name + "...", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                // Attempts in order: SPP to the Classic address, then BLE
                // to the BLE address; PrinterConnection also falls back to
                // the other transport on each address it is given.
                List<String[]> attempts = new ArrayList<String[]>();
                if (r.preferSppFirst()) {
                    if (r.classicMac != null) attempts.add(new String[]{
                            r.classicMac, PrinterConnection.TRANSPORT_SPP});
                    if (r.bleMac != null) attempts.add(new String[]{
                            r.bleMac, PrinterConnection.TRANSPORT_BLE});
                } else {
                    if (r.bleMac != null) attempts.add(new String[]{
                            r.bleMac, PrinterConnection.TRANSPORT_BLE});
                    if (r.classicMac != null) attempts.add(new String[]{
                            r.classicMac, PrinterConnection.TRANSPORT_SPP});
                }
                if (attempts.isEmpty() && r.primaryMac() != null) {
                    SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
                    attempts.add(new String[]{r.primaryMac(),
                            p.getString(KEY_TRANSPORT, null)});
                }
                boolean ok = false;
                for (int i = 0; i < attempts.size() && !ok; i++) {
                    BluetoothDevice device = adapter.getRemoteDevice(attempts.get(i)[0]);
                    ok = connection.connect(MainActivity.this, device, attempts.get(i)[1]);
                }
                final boolean connected = ok;
                if (ok) {
                    SharedPreferences.Editor e =
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                    e.putString(KEY_NAME, r.name);
                    e.putString(KEY_MAC, r.primaryMac());
                    if (r.classicMac != null) e.putString(KEY_MAC_CLASSIC, r.classicMac);
                    else e.remove(KEY_MAC_CLASSIC);
                    if (r.bleMac != null) e.putString(KEY_MAC_BLE, r.bleMac);
                    else e.remove(KEY_MAC_BLE);
                    e.putString(KEY_TRANSPORT, connection.transportName());
                    e.apply();
                    statusSummary = "Connected via " + connection.transportName();
                    refreshInfoOnIoThread();
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        updatePrinterCard();
                        Toast.makeText(MainActivity.this, connected
                                ? "Connected via " + connection.transportName()
                                : "Could not connect to " + r.name,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    // ---------- permissions ----------

    private boolean hasPermission(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensurePermissions() {
        List<String> missing = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (missing.isEmpty()) return true;
        requestPermissions(missing.toArray(new String[missing.size()]), REQ_PERMS);
        return false;
    }

    // ---------- text printing ----------

    /** Renders the text to a 384-wide black-on-white bitmap and previews it. Returns false when empty. */
    private boolean renderTextPreview() {
        String content = textEdit.getText().toString();
        if (content.trim().length() == 0) {
            Toast.makeText(this, "Write something first", Toast.LENGTH_SHORT).show();
            return false;
        }
        int width = PrinterProtocol.PRINT_WIDTH;
        int pad = 10;
        float textSize = 16 + textSizeBar.getProgress();
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(textSize);
        Typeface family = textFont == 1 ? Typeface.SERIF
                : textFont == 2 ? Typeface.MONOSPACE : Typeface.SANS_SERIF;
        paint.setTypeface(Typeface.create(family,
                boldCheck.isChecked() ? Typeface.BOLD : Typeface.NORMAL));
        paint.setAntiAlias(true);
        List<String> lines = wrapText(content, paint, width - 2 * pad);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = (fm.descent - fm.ascent) * spacingMult;
        int height = (int) (pad * 2 + lines.size() * lineHeight + 0.5f);
        if (height < 24) height = 24;
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        float y = pad - fm.ascent;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int x = pad + TextLayout.lineXOffset(textAlign,
                    width - 2 * pad, (int) (paint.measureText(line) + 0.5f));
            canvas.drawText(line, x, y, paint);
            y += lineHeight;
        }
        int[] pixels = new int[width * height];
        bmp.getPixels(pixels, 0, width, 0, 0, width, height);
        boolean[] black = Rasterizer.threshold(Rasterizer.toGray(stripAlpha(pixels)));
        // Invert flips the whole block, padding included; the preview is
        // rebuilt from the final raster so it matches the print exactly.
        textBlack = TextLayout.applyInvert(black, invertCheck.isChecked());
        textBlackW = width;
        textBlackH = height;
        int[] previewPx = new int[width * height];
        for (int i = 0; i < previewPx.length; i++) {
            previewPx[i] = textBlack[i] ? 0xFF000000 : 0xFFFFFFFF;
        }
        Bitmap preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        preview.setPixels(previewPx, 0, width, 0, 0, width, height);
        textPreview.setImageBitmap(preview);
        textPreview.setVisibility(View.VISIBLE);
        return true;
    }

    private static int[] stripAlpha(int[] argb) {
        int[] rgb = new int[argb.length];
        for (int i = 0; i < argb.length; i++) rgb[i] = argb[i] & 0xFFFFFF;
        return rgb;
    }

    private List<String> wrapText(String content, Paint paint, float maxWidth) {
        List<String> lines = new ArrayList<String>();
        String[] paragraphs = content.split("\n", -1);
        for (int p = 0; p < paragraphs.length; p++) {
            String para = paragraphs[p];
            if (para.length() == 0) {
                lines.add("");
                continue;
            }
            String[] words = para.split(" ");
            StringBuilder line = new StringBuilder();
            for (int w = 0; w < words.length; w++) {
                String word = words[w];
                // Break a single word that is wider than the paper.
                while (paint.measureText(word) > maxWidth && word.length() > 1) {
                    int fit = paint.breakText(word, true, maxWidth, null);
                    if (fit <= 0) fit = 1;
                    if (line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    lines.add(word.substring(0, fit));
                    word = word.substring(fit);
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (paint.measureText(candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    // ---------- image printing ----------

    private void loadImage(Bitmap bmp) {
        int targetW = PrinterProtocol.PRINT_WIDTH;
        int targetH = (int) ((long) bmp.getHeight() * targetW / bmp.getWidth());
        if (targetH < 1) targetH = 1;
        if (targetH > 1600) {
            targetW = (int) ((long) targetW * 1600 / targetH);
            targetH = 1600;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
        int[] pixels = new int[targetW * targetH];
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH);
        imageGray = Rasterizer.toGray(stripAlpha(pixels));
        imageW = targetW;
        imageH = targetH;
        processImage();
    }

    /** Rebuilds the printable black map and the preview from the loaded image, per the Dither toggle. */
    private void processImage() {
        if (imageGray == null) return;
        imageBlack = ditherCheck.isChecked()
                ? Rasterizer.floydSteinberg(imageGray, imageW, imageH)
                : Rasterizer.threshold(imageGray);
        int[] px = new int[imageW * imageH];
        for (int i = 0; i < px.length; i++) px[i] = imageBlack[i] ? 0xFF000000 : 0xFFFFFFFF;
        Bitmap preview = Bitmap.createBitmap(imageW, imageH, Bitmap.Config.ARGB_8888);
        preview.setPixels(px, 0, imageW, 0, 0, imageW, imageH);
        imagePreview.setImageBitmap(preview);
        imagePreview.setVisibility(View.VISIBLE);
    }

    // ---------- printing ----------

    private void printCurrent(final boolean[] black, final int w, final int h) {
        if (!connection.isConnected()) {
            Toast.makeText(this, "Connect a printer first", Toast.LENGTH_SHORT).show();
            return;
        }
        final int dens = density;
        final int nCopies = copies;
        Toast.makeText(this, "Printing...", Toast.LENGTH_SHORT).show();
        io.execute(new Runnable() {
            @Override public void run() {
                final String error = connection.print(black, w, h, dens, nCopies);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this,
                                error == null ? "Printed" : error,
                                Toast.LENGTH_LONG).show();
                    }
                });
                if (error == null) refreshInfoOnIoThread();
            }
        });
    }
}
