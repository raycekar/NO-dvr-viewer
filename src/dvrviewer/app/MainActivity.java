package dvrviewer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DVR Cam — a grid viewer that polls JPEG snapshots from a NightOwl/Xiongmai
 * DVR's HTTP endpoint (/cgi-bin/guest/Video.cgi?channel=N).
 *
 *  • Grid of tiles, one per channel (tiles fill via CENTER_CROP by default).
 *  • Tap a tile to open a single-channel fullscreen view.
 *      – swipe left/right to change channel
 *      – pinch / double-tap to zoom, drag to pan
 *      – tap (or Back) to return to the grid
 *  • Gear (top-right): DVR IP, username, password, feed count, 1–5 s refresh,
 *    and a "crop tiles to fill" toggle. Credentials stay in private prefs.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "dvr";
    private static final String PATH  = "/cgi-bin/guest/Video.cgi?channel=";
    private static final int GRID = 0, SINGLE = 1;

    private SharedPreferences sp;
    private FrameLayout root;
    private LinearLayout container;
    private ImageButton gear;
    private ImageView[] cells = new ImageView[0];
    private TextView[] stamps = new TextView[0];
    private TextView singleStamp;
    private final SimpleDateFormat tfmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private int mode = GRID;
    private int focused = 0;
    private FrameLayout singleOverlay;
    private TouchImageView singleView;
    private TextView singleTag;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool = Executors.newFixedThreadPool(4);
    private Runnable tick;
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Keep the display awake while the app is in the foreground (released automatically
        // when the activity is no longer visible).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        root.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        gear = new ImageButton(this);
        gear.setImageResource(android.R.drawable.ic_menu_preferences);
        gear.setBackgroundColor(0x80000000);
        int gm = dp(8);
        gear.setPadding(gm, gm, gm, gm);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = Gravity.TOP | Gravity.END;
        glp.setMargins(0, dp(12), dp(12), 0);
        gear.setLayoutParams(glp);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        root.addView(gear);

        setContentView(root);
        buildGrid();

        if (sp.getString("ip", "").isEmpty()) {
            ui.post(new Runnable() { @Override public void run() { openSettings(); } });
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int feedCount() { return clamp(sp.getInt("count", 4), 1, 16); }
    private int interval()  { return clamp(sp.getInt("interval", 1), 1, 5); }
    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private String basicAuth() {
        String u = sp.getString("user", ""), p = sp.getString("pass", "");
        return "Basic " + Base64.encodeToString((u + ":" + p).getBytes(), Base64.NO_WRAP);
    }

    /**
     * Rebuild the camera layout for the current feed count. The layout is chosen
     * automatically from the device orientation: portrait -> scrolling list,
     * landscape -> quadrant grid.
     */
    private void buildGrid() {
        stopPolling();
        int n = feedCount();
        container.removeAllViews();
        cells = new ImageView[n];
        stamps = new TextView[n];
        if (isLandscape()) buildQuad(n); else buildList(n);
        startPolling();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Rotation is handled in-place (see manifest configChanges); swap the layout
        // to match the new orientation.
        buildGrid();
    }

    /** Full-width vertical scrolling list: one camera per row. */
    private void buildList(int n) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(6);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int rowH = Math.round((getResources().getDisplayMetrics().widthPixels - dp(12)) * 9f / 16f);
        for (int i = 0; i < n; i++) {
            FrameLayout tile = newTile(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH);
            lp.setMargins(0, 0, 0, dp(6));
            col.addView(tile, lp);
        }
        container.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** Square-ish quadrant grid using weighted rows/columns. */
    private void buildQuad(int n) {
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil((double) n / cols);
        int made = 0;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            container.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < cols; c++) {
                LinearLayout.LayoutParams clp =
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (made < n) {
                    int m = dp(1);
                    clp.setMargins(m, m, m, m);
                    row.addView(newTile(made), clp);
                    made++;
                } else {
                    row.addView(new View(this), clp);
                }
            }
        }
    }

    /** A tile holding the channel's zoomable view (registered in cells[]) and labels.
     *  Pinch/double-tap zooms the feed in place; a single tap opens the fullscreen view. */
    private FrameLayout newTile(final int index) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundColor(0xFF0E0E0E);

        TouchImageView iv = new TouchImageView(this);
        iv.setSwipeEnabled(false);   // no channel-swiping on tiles; swipe is for the single view
        iv.setListener(new TouchImageView.Listener() {
            @Override public void onSwipe(int dir) { /* disabled on tiles */ }
            @Override public void onTap() { showSingle(index); }
        });
        tile.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView tag = new TextView(this);
        tag.setText("CH " + (index + 1));
        tag.setTextColor(0xFFFFFFFF);
        tag.setBackgroundColor(0x80000000);
        tag.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP | Gravity.START;
        tlp.setMargins(dp(4), dp(4), 0, 0);
        tile.addView(tag, tlp);

        TextView stamp = new TextView(this);
        stamp.setText("—");
        stamp.setTextColor(0xFFB0FFB0);
        stamp.setBackgroundColor(0x80000000);
        stamp.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.END;
        slp.setMargins(0, 0, dp(4), dp(4));
        tile.addView(stamp, slp);

        cells[index] = iv;
        stamps[index] = stamp;
        return tile;
    }

    private void showSingle(int index) {
        focused = index;
        mode = SINGLE;
        if (singleOverlay == null) {
            singleOverlay = new FrameLayout(this);
            singleOverlay.setBackgroundColor(Color.BLACK);

            singleView = new TouchImageView(this);
            singleOverlay.addView(singleView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            singleTag = new TextView(this);
            singleTag.setTextColor(0xFFFFFFFF);
            singleTag.setBackgroundColor(0x80000000);
            singleTag.setPadding(dp(8), dp(4), dp(8), dp(4));
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.gravity = Gravity.TOP | Gravity.START;
            tlp.setMargins(dp(8), dp(8), 0, 0);
            singleOverlay.addView(singleTag, tlp);

            singleStamp = new TextView(this);
            singleStamp.setText("—");
            singleStamp.setTextColor(0xFFB0FFB0);
            singleStamp.setBackgroundColor(0x80000000);
            singleStamp.setPadding(dp(8), dp(4), dp(8), dp(4));
            FrameLayout.LayoutParams sslp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sslp.gravity = Gravity.BOTTOM | Gravity.END;
            sslp.setMargins(0, 0, dp(8), dp(8));
            singleOverlay.addView(singleStamp, sslp);

            singleView.setListener(new TouchImageView.Listener() {
                @Override public void onSwipe(int dir) { switchChannel(dir); }
                @Override public void onTap() { exitSingle(); }
            });
        }
        singleView.setImageDrawable(null);
        singleView.resetZoom();
        if (singleStamp != null) singleStamp.setText("—");
        updateSingleTag();
        if (singleOverlay.getParent() == null) root.addView(singleOverlay);
        singleOverlay.setVisibility(View.VISIBLE);
        singleOverlay.bringToFront();
        container.setVisibility(View.GONE);
        gear.setVisibility(View.GONE);
        startPolling();
    }

    private void switchChannel(int dir) {
        int n = feedCount();
        focused = (focused + dir + n) % n;
        singleView.setImageDrawable(null);
        singleView.resetZoom();
        if (singleStamp != null) singleStamp.setText("—");
        updateSingleTag();
        fetchSingle();
    }

    private void updateSingleTag() {
        singleTag.setText("CH " + (focused + 1) + "   ·   swipe ⇄   ·   pinch to zoom   ·   tap to exit");
    }

    private void exitSingle() {
        mode = GRID;
        if (singleOverlay != null) singleOverlay.setVisibility(View.GONE);
        container.setVisibility(View.VISIBLE);
        gear.setVisibility(View.VISIBLE);
        startPolling();
    }

    @Override public void onBackPressed() {
        if (mode == SINGLE) exitSingle();
        else super.onBackPressed();
    }

    private void startPolling() {
        stopPolling();
        running = true;
        tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                if (mode == SINGLE) fetchSingle(); else fetchGrid();
                ui.postDelayed(this, interval() * 1000L);
            }
        };
        ui.post(tick);
    }

    private void stopPolling() {
        running = false;
        if (tick != null) ui.removeCallbacks(tick);
    }

    private void fetchGrid() {
        final String ip = sp.getString("ip", "").trim();
        if (ip.isEmpty()) return;
        final String auth = basicAuth();
        for (int i = 0; i < cells.length; i++) {
            final int idx = i;
            final String url = "http://" + ip + PATH + (i + 1);
            pool.submit(new Runnable() {
                @Override public void run() {
                    final Bitmap bm = fetch(url, auth);
                    if (bm == null) return;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (mode == GRID && idx < cells.length && cells[idx] != null) {
                                cells[idx].setImageBitmap(bm);
                                if (stamps[idx] != null) stamps[idx].setText(tfmt.format(new Date()));
                            }
                        }
                    });
                }
            });
        }
    }

    private void fetchSingle() {
        final String ip = sp.getString("ip", "").trim();
        if (ip.isEmpty()) return;
        final String auth = basicAuth();
        final int ch = focused + 1;
        final String url = "http://" + ip + PATH + ch;
        pool.submit(new Runnable() {
            @Override public void run() {
                final Bitmap bm = fetch(url, auth);
                if (bm == null) return;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (mode == SINGLE && focused + 1 == ch && singleView != null) {
                            singleView.setImageBitmap(bm);
                            if (singleStamp != null) singleStamp.setText(tfmt.format(new Date()));
                        }
                    }
                });
            }
        });
    }

    private Bitmap fetch(String url, String authHeader) {
        HttpURLConnection c = null;
        InputStream is = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Authorization", authHeader);
            c.setRequestProperty("Connection", "close");
            if (c.getResponseCode() != 200) return null;
            is = c.getInputStream();
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
        }
    }

    private void openSettings() {
        final LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        v.setPadding(p, dp(8), p, 0);

        final EditText ip = new EditText(this);
        ip.setHint("DVR IP address");
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ip.setText(sp.getString("ip", ""));

        final EditText user = new EditText(this);
        user.setHint("Username");
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        user.setText(sp.getString("user", ""));

        final EditText pass = new EditText(this);
        pass.setHint("Password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setText(sp.getString("pass", ""));

        final TextView countLabel = new TextView(this);
        countLabel.setPadding(0, dp(16), 0, 0);
        countLabel.setText("Feeds to show: " + feedCount());
        final SeekBar count = new SeekBar(this);
        count.setMax(15);
        count.setProgress(feedCount() - 1);
        count.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                countLabel.setText("Feeds to show: " + (prog + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        final TextView intLabel = new TextView(this);
        intLabel.setPadding(0, dp(16), 0, 0);
        intLabel.setText("Update every " + interval() + " s");
        final SeekBar interval = new SeekBar(this);
        interval.setMax(4);
        interval.setProgress(interval() - 1);
        interval.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                intLabel.setText("Update every " + (prog + 1) + " s");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        final TextView note = new TextView(this);
        note.setText("Layout follows orientation: portrait = list, landscape = grid.");
        note.setPadding(0, dp(16), 0, 0);
        note.setTextColor(0xFF888888);

        v.addView(ip);
        v.addView(user);
        v.addView(pass);
        v.addView(countLabel);
        v.addView(count);
        v.addView(intLabel);
        v.addView(interval);
        v.addView(note);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(v);

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(scroller)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit()
                                .putString("ip", ip.getText().toString().trim())
                                .putString("user", user.getText().toString())
                                .putString("pass", pass.getText().toString())
                                .putInt("count", count.getProgress() + 1)
                                .putInt("interval", interval.getProgress() + 1)
                                .apply();
                        if (mode == SINGLE) exitSingle();
                        buildGrid();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override protected void onResume()  { super.onResume();  if (cells.length > 0) startPolling(); }
    @Override protected void onPause()   { super.onPause();   stopPolling(); }
    @Override protected void onDestroy() { super.onDestroy(); stopPolling(); pool.shutdownNow(); }
}
