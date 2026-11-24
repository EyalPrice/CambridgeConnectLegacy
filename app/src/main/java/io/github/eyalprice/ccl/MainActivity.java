package io.github.eyalprice.ccl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String PREF_LOCATION = "location";
    private static final String PREF_PINS = "pins";

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private Upnp.Device device;
    private LinearLayout content;
    private TextView status;
    private TextView volumeLabel;
    private int volume = -1;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ccl", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout tabs = row();
        tabs.addView(weighted(button("Remote", v -> showRemote())));
        tabs.addView(weighted(button("Commands", v -> showCommands())));
        tabs.addView(weighted(button("Find stereo", v -> showScan())));
        root.addView(tabs);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setTextSize(14);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        String loc = prefs.getString(PREF_LOCATION, null);
        if (loc != null) connect(loc);
        else showScan();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ connect

    private void connect(String location) {
        setStatus("Connecting…");
        bg.submit(() -> {
            try {
                Upnp.Device d = Upnp.loadDevice(location);
                ui.post(() -> {
                    device = d;
                    prefs.edit().putString(PREF_LOCATION, location).apply();
                    setStatus("Connected: " + d.friendlyName + "  (" + d.host() + ")");
                    showRemote();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    setStatus("Could not reach the saved stereo: " + e.getMessage()
                            + "\nIs it switched on (not in deep standby) and on the same Wi-Fi?");
                    showScan();
                });
            }
        });
    }

    // ------------------------------------------------------------------ scan screen

    private void showScan() {
        content.removeAllViews();
        content.addView(heading("Find the stereo"));
        content.addView(note("The phone and the stereo (Minx Xi, NP30, Stream Magic 6) must be on the same Wi-Fi network."));

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);

        content.addView(note("Unofficial community app. Not made by, endorsed by or affiliated with Cambridge Audio. "
                + "Cambridge Audio, Minx and Stream Magic are trademarks of their owner."));
        content.addView(button("Search network", v -> runSearch(null, results)));

        content.addView(note("Or type the stereo's IP address (shown on its Network config menu):"));
        EditText ip = new EditText(this);
        ip.setHint("e.g. 192.168.1.50");
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        content.addView(ip);
        content.addView(button("Search this address", v -> {
            String t = ip.getText().toString().trim();
            if (t.isEmpty()) return;
            if (t.startsWith("http")) connect(t);
            else runSearch(t, results);
        }));
        content.addView(results);
    }

    private void runSearch(String ip, LinearLayout results) {
        results.removeAllViews();
        setStatus("Searching…");
        bg.submit(() -> {
            try {
                List<Upnp.Found> found = Upnp.discover(this, ip, 4000);
                for (Upnp.Found f : found) Upnp.peek(f);
                ui.post(() -> showFound(found, results));
            } catch (Exception e) {
                ui.post(() -> setStatus("Search failed: " + e.getMessage()));
            }
        });
    }

    private void showFound(List<Upnp.Found> found, LinearLayout results) {
        results.removeAllViews();
        if (found.isEmpty()) {
            setStatus("No devices answered. Check the stereo is on and connected to Wi-Fi.");
            return;
        }
        setStatus(found.size() + " device(s) found. Tap your Cambridge Audio stereo (marked ★).");
        results.addView(heading("Devices on your network"));
        for (Upnp.Found f : found) {
            String host;
            try { host = new java.net.URL(f.location).getHost(); } catch (Exception e) { host = f.location; }
            String text = (f.name.isEmpty() ? "(no name)" : f.name) + "\n" + f.model + "  ·  " + host;
            Button b = button(text, v -> connect(f.location));
            String lower = (f.name + " " + f.model + " " + f.server).toLowerCase(Locale.ROOT);
            if (lower.contains("minx") || lower.contains("cambridge") || lower.contains("stream magic") || lower.contains("np30")) {
                b.setTypeface(Typeface.DEFAULT_BOLD);
                b.setText("★ " + text);
            }
            results.addView(b);
        }
    }

    // ------------------------------------------------------------------ remote screen

    private void showRemote() {
        content.removeAllViews();
        if (device == null) {
            content.addView(note("No stereo connected yet. Tap “Find stereo”."));
            return;
        }
        content.addView(heading(device.friendlyName));

        Upnp.Service rc = device.findService("RenderingControl");
        if (rc != null) {
            content.addView(heading("Volume"));
            volumeLabel = new TextView(this);
            volumeLabel.setTextSize(28);
            volumeLabel.setGravity(Gravity.CENTER);
            volumeLabel.setText("–");
            content.addView(volumeLabel);
            LinearLayout r = row();
            r.addView(weighted(big("−", v -> changeVolume(rc, -2))));
            r.addView(weighted(big("Mute", v -> toggleMute(rc))));
            r.addView(weighted(big("+", v -> changeVolume(rc, +2))));
            content.addView(r);
            refreshVolume(rc);
        }

        Upnp.Service av = device.findService("AVTransport");
        if (av != null) {
            content.addView(heading("Playback"));
            LinearLayout r = row();
            r.addView(weighted(big("⏮", v -> simple(av, "Previous"))));
            r.addView(weighted(big("▶", v -> play(av))));
            r.addView(weighted(big("⏸", v -> simple(av, "Pause"))));
            r.addView(weighted(big("⏹", v -> simple(av, "Stop"))));
            r.addView(weighted(big("⏭", v -> simple(av, "Next"))));
            content.addView(r);
        }

        content.addView(heading("My buttons (inputs, power…)"));
        JSONArray pins = loadPins();
        if (pins.length() == 0) {
            content.addView(note("No buttons yet. Open “Commands”, run a command (for example one that "
                    + "sets the input/source), then choose “Pin to remote”."));
        }
        for (int i = 0; i < pins.length(); i++) {
            JSONObject p = pins.optJSONObject(i);
            if (p == null) continue;
            final int index = i;
            Button b = big(p.optString("label", "?"), v -> runPin(p));
            b.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Remove button “" + p.optString("label") + "”?")
                        .setPositiveButton("Remove", (d, w) -> { removePin(index); showRemote(); })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
            content.addView(b);
        }
        if (pins.length() > 0) content.addView(note("Long-press a button to remove it."));
    }

    private Map<String, String> instance() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("InstanceID", "0");
        return m;
    }

    private void refreshVolume(Upnp.Service rc) {
        Map<String, String> a = instance();
        a.put("Channel", "Master");
        callAsync(rc, "GetVolume", a, r -> {
            String v = r.out.get("CurrentVolume");
            if (v != null) {
                try { volume = Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) { }
                if (volumeLabel != null) volumeLabel.setText(v.trim());
            }
        }, false);
    }

    private void changeVolume(Upnp.Service rc, int delta) {
        if (volume < 0) { refreshVolume(rc); return; }
        int target = Math.max(0, Math.min(100, volume + delta));
        Map<String, String> a = instance();
        a.put("Channel", "Master");
        a.put("DesiredVolume", String.valueOf(target));
        callAsync(rc, "SetVolume", a, r -> refreshVolume(rc), true);
    }

    private void toggleMute(Upnp.Service rc) {
        Map<String, String> a = instance();
        a.put("Channel", "Master");
        callAsync(rc, "GetMute", a, r -> {
            String cur = r.out.get("CurrentMute");
            boolean muted = "1".equals(cur) || "true".equalsIgnoreCase(cur);
            Map<String, String> s = instance();
            s.put("Channel", "Master");
            s.put("DesiredMute", muted ? "0" : "1");
            callAsync(rc, "SetMute", s, r2 -> setStatus(muted ? "Unmuted" : "Muted"), true);
        }, true);
    }

    private void play(Upnp.Service av) {
        Map<String, String> a = instance();
        a.put("Speed", "1");
        callAsync(av, "Play", a, r -> setStatus("Play"), true);
    }

    private void simple(Upnp.Service av, String action) {
        callAsync(av, action, instance(), r -> setStatus(action), true);
    }

    // ------------------------------------------------------------------ commands screen

    private void showCommands() {
        content.removeAllViews();
        if (device == null) {
            content.addView(note("No stereo connected yet. Tap “Find stereo”."));
            return;
        }
        content.addView(heading("All commands of " + device.friendlyName));
        content.addView(note(device.manufacturer + " " + device.modelName + " " + device.modelNumber));
        content.addView(button("Share this list (send it to Claude)", v -> shareReport()));
        content.addView(note("Tap a command to run it. Commands starting with “Get” only read information and are safe to try."));

        for (Upnp.Service s : device.services) {
            content.addView(heading(s.shortType()));
            if (s.error != null) content.addView(note("Could not read this service: " + s.error));
            for (Upnp.Action a : s.actions) {
                StringBuilder label = new StringBuilder(a.name);
                List<Upnp.Arg> in = a.inArgs();
                if (!in.isEmpty()) {
                    label.append("  (");
                    for (int i = 0; i < in.size(); i++) {
                        if (i > 0) label.append(", ");
                        label.append(in.get(i).name);
                    }
                    label.append(")");
                }
                Button b = button(label.toString(), v -> askArgsAndRun(s, a));
                b.setAllCaps(false);
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                content.addView(b);
            }
        }
    }

    private void askArgsAndRun(Upnp.Service s, Upnp.Action a) {
        List<Upnp.Arg> in = a.inArgs();
        if (in.isEmpty()) {
            runAndShow(s, a.name, new LinkedHashMap<>());
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), 0);
        List<EditText> fields = new ArrayList<>();
        for (Upnp.Arg arg : in) {
            TextView t = new TextView(this);
            String hint = arg.name;
            if (!arg.allowed.isEmpty()) hint += "\nAllowed: " + String.join(", ", arg.allowed);
            if (!arg.range.isEmpty()) hint += "\nRange: " + arg.range;
            t.setText(hint);
            form.addView(t);
            EditText e = new EditText(this);
            e.setSingleLine(true);
            if (arg.name.equals("InstanceID")) e.setText("0");
            else if (arg.name.equals("Channel")) e.setText("Master");
            else if (arg.name.equals("Speed")) e.setText("1");
            else if (!arg.allowed.isEmpty()) e.setText(arg.allowed.get(0));
            form.addView(e);
            fields.add(e);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        new AlertDialog.Builder(this)
                .setTitle(a.name)
                .setView(sv)
                .setPositiveButton("Run", (d, w) -> {
                    Map<String, String> args = new LinkedHashMap<>();
                    for (int i = 0; i < in.size(); i++) args.put(in.get(i).name, fields.get(i).getText().toString());
                    runAndShow(s, a.name, args);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runAndShow(Upnp.Service s, String action, Map<String, String> args) {
        callAsync(s, action, args, r -> {
            StringBuilder sb = new StringBuilder();
            sb.append(r.ok() ? "OK" : "Error (HTTP " + r.httpCode + ")").append("\n\n");
            for (Map.Entry<String, String> e : r.out.entrySet()) sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            if (r.out.isEmpty() || !r.ok()) {
                String raw = r.body.length() > 3000 ? r.body.substring(0, 3000) + "…" : r.body;
                sb.append("\nRaw reply:\n").append(raw);
            }
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle(action)
                    .setMessage(sb.toString())
                    .setNegativeButton("Close", null);
            if (r.ok()) b.setPositiveButton("Pin to remote", (d, w) -> askPinLabel(s, action, args));
            b.show();
        }, false);
    }

    // ------------------------------------------------------------------ pins

    private void askPinLabel(Upnp.Service s, String action, Map<String, String> args) {
        EditText e = new EditText(this);
        String guess = action;
        for (String v : args.values()) if (!v.equals("0") && !v.equals("Master")) guess = v;
        e.setText(guess);
        new AlertDialog.Builder(this)
                .setTitle("Button name")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        JSONObject p = new JSONObject();
                        p.put("label", e.getText().toString().trim());
                        p.put("serviceType", s.type);
                        p.put("action", action);
                        JSONObject a = new JSONObject();
                        for (Map.Entry<String, String> en : args.entrySet()) a.put(en.getKey(), en.getValue());
                        p.put("args", a);
                        JSONArray pins = loadPins();
                        pins.put(p);
                        prefs.edit().putString(PREF_PINS, pins.toString()).apply();
                        setStatus("Button saved on the Remote screen.");
                    } catch (Exception ex) {
                        setStatus("Could not save: " + ex.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runPin(JSONObject p) {
        Upnp.Service s = device.serviceByType(p.optString("serviceType"));
        if (s == null) { setStatus("This stereo doesn't have the service for that button."); return; }
        Map<String, String> args = new LinkedHashMap<>();
        JSONObject a = p.optJSONObject("args");
        if (a != null) {
            Iterator<String> keys = a.keys();
            while (keys.hasNext()) { String k = keys.next(); args.put(k, a.optString(k)); }
        }
        String label = p.optString("label");
        callAsync(s, p.optString("action"), args, r -> setStatus(label), true);
    }

    private JSONArray loadPins() {
        try { return new JSONArray(prefs.getString(PREF_PINS, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void removePin(int index) {
        JSONArray pins = loadPins();
        pins.remove(index);
        prefs.edit().putString(PREF_PINS, pins.toString()).apply();
    }

    // ------------------------------------------------------------------ report

    private void shareReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Cambridge Connect Legacy device report\n");
        sb.append("Name: ").append(device.friendlyName).append('\n');
        sb.append("Model: ").append(device.manufacturer).append(' ').append(device.modelName)
          .append(' ').append(device.modelNumber).append('\n');
        sb.append("Description path: ").append(pathOnly(device.location)).append("\n\n");
        for (Upnp.Service s : device.services) {
            sb.append("SERVICE ").append(s.type).append('\n');
            sb.append("  control: ").append(pathOnly(s.controlUrl)).append('\n');
            if (s.error != null) sb.append("  error: ").append(s.error).append('\n');
            for (Upnp.Action a : s.actions) {
                sb.append("  ").append(a.name).append('\n');
                for (Upnp.Arg g : a.args) {
                    sb.append("      ").append(g.direction).append(' ').append(g.name);
                    if (!g.allowed.isEmpty()) sb.append("  allowed=[").append(String.join("|", g.allowed)).append(']');
                    if (!g.range.isEmpty()) sb.append("  range=").append(g.range);
                    sb.append('\n');
                }
            }
            sb.append('\n');
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Cambridge Connect Legacy - stereo command list");
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(i, "Share command list"));
    }

    /** Report leaves out the home IP address on purpose. */
    private static String pathOnly(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            return ":" + u.getPort() + u.getFile();
        } catch (Exception e) {
            return url;
        }
    }

    // ------------------------------------------------------------------ async helper

    private interface OnResult { void done(Upnp.Result r); }

    private void callAsync(Upnp.Service s, String action, Map<String, String> args, OnResult then, boolean showErrors) {
        bg.submit(() -> {
            try {
                Upnp.Result r = Upnp.call(s, action, args);
                ui.post(() -> {
                    if (!r.ok() && showErrors) {
                        setStatus(action + " failed (HTTP " + r.httpCode + ")");
                        return;
                    }
                    then.done(r);
                });
            } catch (Exception e) {
                ui.post(() -> setStatus(action + " failed: " + e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------ small UI helpers

    private void setStatus(String s) { status.setText(s); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private View weighted(View v) {
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Button big(String text, View.OnClickListener l) {
        Button b = button(text, l);
        b.setTextSize(22);
        b.setMinHeight(dp(64));
        return b;
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.rgb(0x1F, 0x2A, 0x44));
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }
}
