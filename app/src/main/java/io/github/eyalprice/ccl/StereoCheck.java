package io.github.eyalprice.ccl;

import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stereo check: runs read-only commands, captures per-source snapshots and (with consent)
 * a reversible change test, and builds a text report with private details removed.
 * All methods block: call them from a background thread.
 */
public final class StereoCheck {

    public interface Progress { void update(String text); }

    private static final String NAV_NAME = "CCL-check";
    private static final int MAX_OUT = 2500;
    private static final int MAX_ERR = 1200;

    /** Actions whose names start with these only read information. */
    private static final String[] READ_PREFIX = {
            "Get", "List", "Is", "Browse", "Read", "Repeat", "Shuffle", "TracksMax", "IdArray", "Search"
    };

    /** Called once per source during the source tour, when the device has them. */
    private static final String[] SNAPSHOT = {
            "GetPowerState", "GetAudioSource", "GetAudioSourceByNumber", "GetVolume", "GetMute",
            "GetTransportInfo", "GetMediaInfo", "GetPositionInfo", "GetCurrentTransportActions",
            "GetPlaybackDetails", "GetStationId", "GetSpotifyStatus", "GetSpotifyPlaybackPosition",
            "GetPlaylistLength", "GetCurrentPlaylistTrack", "GetMediaQueueIndex",
            "GetIsPlayingItemIdForNavigator"
    };

    private final Upnp.Device dev;
    private final String appVersion;
    private final StringBuilder readPart = new StringBuilder();
    private final StringBuilder tourPart = new StringBuilder();
    private final StringBuilder changePart = new StringBuilder();
    private String navId;
    private int readOk, readFail, readSkipped, tourCount, changePass, changeFail;

    public StereoCheck(Upnp.Device dev, String appVersion) {
        this.dev = dev;
        this.appVersion = appVersion;
    }

    public int tourCount() { return tourCount; }

    public String readSummary() {
        return readOk + " answered, " + readFail + " errors, " + readSkipped + " skipped";
    }

    public String changeSummary() {
        return changePass + " passed, " + changeFail + " failed";
    }

    // ------------------------------------------------------------------ 1. read test

    public static boolean isRead(String action) {
        if (action.startsWith("Set")) return false;
        for (String p : READ_PREFIX) if (action.startsWith(p)) return true;
        return false;
    }

    public void runReadTest(Progress p) {
        readPart.setLength(0);
        readOk = readFail = readSkipped = 0;
        ensureNavigator();
        int total = 0, done = 0;
        for (Upnp.Service s : dev.services) for (Upnp.Action a : s.actions) if (isRead(a.name)) total++;

        for (Upnp.Service s : dev.services) {
            readPart.append("\n=== ").append(s.type).append(" ===\n");
            if (s.error != null) readPart.append("service description error: ").append(s.error).append('\n');
            for (Upnp.Action a : s.actions) {
                if (!isRead(a.name)) continue;
                done++;
                p.update("Reading " + done + " of " + total + ": " + a.name);
                if (a.name.equals("GetAudioSourceName")) {
                    for (int i = 0; i < 8; i++) runOne(s, a, readPart, "InAudioSource", String.valueOf(i));
                } else if (a.name.equals("GetPreset")) {
                    for (int i = 1; i <= 3; i++) runOne(s, a, readPart, "RetPresetNumberValue", String.valueOf(i));
                } else {
                    runOne(s, a, readPart, null, null);
                }
            }
        }
        p.update("Read test finished: " + readSummary());
    }

    private void runOne(Upnp.Service s, Upnp.Action a, StringBuilder out, String overrideArg, String overrideVal) {
        Map<String, String> args = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Upnp.Arg g : a.inArgs()) {
            String v = g.name.equals(overrideArg) ? overrideVal : fill(g);
            if (v == null) missing.add(g.name);
            else args.put(g.name, v);
        }
        out.append("\n--- ").append(a.name);
        if (!args.isEmpty()) out.append("  ").append(args);
        out.append('\n');
        if (!missing.isEmpty()) {
            readSkipped++;
            out.append("SKIPPED: needs ").append(missing).append('\n');
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            Upnp.Result r = Upnp.call(s, a.name, args);
            long ms = System.currentTimeMillis() - t0;
            out.append("HTTP ").append(r.httpCode).append(" (").append(ms).append(" ms)\n");
            if (r.ok()) {
                readOk++;
                for (Map.Entry<String, String> e : r.out.entrySet()) {
                    out.append("  ").append(e.getKey()).append(" = ").append(cut(e.getValue(), MAX_OUT)).append('\n');
                }
            } else {
                readFail++;
                out.append("  error: ").append(cut(r.body, MAX_ERR)).append('\n');
            }
        } catch (Exception e) {
            readFail++;
            out.append("EXCEPTION: ").append(e).append('\n');
        }
    }

    /** Default values for arguments of read commands. Null means "can't guess". */
    private String fill(Upnp.Arg g) {
        switch (g.name) {
            case "InstanceID": case "ConnectionID": case "ObjectID": case "StartingIndex":
            case "StartTrackID": case "aId": case "aIdArrayToken": case "Offset": case "MinBitrateFilter":
                return "0";
            case "Channel": return "Master";
            case "BrowseFlag": return "BrowseDirectChildren";
            case "Filter": return "*";
            case "RequestedCount": case "TrackCount": case "Count": return "20";
            case "SortCriteria": case "aIdList": case "CodecFilter": case "LocationIDFilter": case "GenreIDFilter":
                return "";
            case "NavigatorId": return navId;
            case "NavigatorName": case "NewNavigatorName": return NAV_NAME;
            case "Start": return "1";
            case "End": return "99";
            case "RetPresetNumberValue": return "1";
            case "InAudioSource": return "0";
            case "StationName": case "NameFilter": return "jazz";
            default:
                if (!g.allowed.isEmpty()) return g.allowed.get(0);
                return null;
        }
    }

    private void ensureNavigator() {
        if (navId != null) return;
        Upnp.Service s = findAction("RegisterNamedNavigator");
        if (s == null) return;
        try {
            Map<String, String> a = new LinkedHashMap<>();
            a.put("NewNavigatorName", NAV_NAME);
            Upnp.Result r = Upnp.call(s, "RegisterNamedNavigator", a);
            navId = r.out.get("RetNavigatorId");
        } catch (Exception ignored) {
            // read test still runs; navigator commands are then skipped
        }
    }

    public void releaseNavigator() {
        Upnp.Service s = findAction("ReleaseNavigator");
        if (s == null || navId == null) return;
        try {
            Map<String, String> a = new LinkedHashMap<>();
            a.put("NavigatorId", navId);
            Upnp.call(s, "ReleaseNavigator", a);
        } catch (Exception ignored) { }
        navId = null;
    }

    private Upnp.Service findAction(String name) {
        for (Upnp.Service s : dev.services) if (s.action(name) != null) return s;
        return null;
    }

    // ------------------------------------------------------------------ 2. source tour

    public void captureSource(String label, Progress p) {
        ensureNavigator();
        tourCount++;
        tourPart.append("\n=== Source ").append(tourCount).append(": ").append(label).append(" ===\n");
        for (String name : SNAPSHOT) {
            Upnp.Service s = findAction(name);
            if (s == null) continue;
            p.update("Capturing " + label + ": " + name);
            runOne(s, s.action(name), tourPart, null, null);
        }
        p.update("Captured " + tourCount + " source(s). Switch to the next one, or go on to step 3.");
    }

    // ------------------------------------------------------------------ 3. safe change test

    private static final class Test {
        final String getter, setter, setArg, kind; // kind: int, bool, enum
        Test(String g, String s, String a, String k) { getter = g; setter = s; setArg = a; kind = k; }
    }

    private static final Test[] TESTS = {
            new Test("GetVolume", "SetVolume", "DesiredVolume", "int"),
            new Test("GetMute", "SetMute", "DesiredMute", "bool"),
            new Test("GetLoudness", "SetLoudness", "DesiredLoudness", "bool"),
            new Test("GetBalanceDB", "SetBalanceDB", "DesiredBalanceDB", "int"),
            new Test("GetToneCtrlBass", "SetToneCtrlBass", "NewToneCtrlBassValue", "int"),
            new Test("GetToneCtrlTreble", "SetToneCtrlTreble", "NewToneCtrlTrebleValue", "int"),
            new Test("GetAudioFilter", "SetAudioFilter", "NewAudioFilter", "enum"),
            new Test("GetAudioPhaseInverted", "SetAudioPhaseInverted", "NewAudioPhaseInverted", "bool"),
    };

    public void runChangeTest(Progress p) {
        changePart.setLength(0);
        changePass = changeFail = 0;
        for (Test t : TESTS) {
            Upnp.Service gs = findAction(t.getter);
            Upnp.Service ss = findAction(t.setter);
            if (gs == null || ss == null) continue;
            p.update("Testing " + t.setter + "…");
            changePart.append("\n--- ").append(t.setter).append('\n');
            String orig = null;
            try {
                orig = get(gs, t.getter);
                if (orig == null) { changePart.append("could not read starting value\n"); changeFail++; continue; }
                String next = nextValue(t, orig, ss.action(t.setter));
                changePart.append("start = ").append(orig).append(", try = ").append(next).append('\n');
                Upnp.Result r1 = set(ss, t.setter, t.setArg, next);
                String back = get(gs, t.getter);
                changePart.append("set HTTP ").append(r1.httpCode).append(", read back = ").append(back).append('\n');
                if (!r1.ok()) changePart.append("  error: ").append(cut(r1.body, MAX_ERR)).append('\n');
                boolean ok = r1.ok() && same(next, back);
                if (ok) changePass++; else changeFail++;
                changePart.append(ok ? "PASS\n" : "FAIL\n");
            } catch (Exception e) {
                changeFail++;
                changePart.append("EXCEPTION: ").append(e).append('\n');
            } finally {
                if (orig != null) {
                    try {
                        Upnp.Result r2 = set(ss, t.setter, t.setArg, orig);
                        changePart.append("restored to ").append(orig).append(" (HTTP ").append(r2.httpCode).append(")\n");
                    } catch (Exception e) {
                        changePart.append("RESTORE FAILED: ").append(e).append('\n');
                    }
                }
            }
        }
        p.update("Safe change test finished: " + changeSummary());
    }

    private String get(Upnp.Service s, String action) throws Exception {
        Map<String, String> args = new LinkedHashMap<>();
        for (Upnp.Arg g : s.action(action).inArgs()) {
            String v = fill(g);
            if (v != null) args.put(g.name, v);
        }
        Upnp.Result r = Upnp.call(s, action, args);
        if (!r.ok() || r.out.isEmpty()) return null;
        return r.out.values().iterator().next().trim();
    }

    private Upnp.Result set(Upnp.Service s, String action, String argName, String value) throws Exception {
        Map<String, String> args = new LinkedHashMap<>();
        for (Upnp.Arg g : s.action(action).inArgs()) {
            if (g.name.equals(argName)) args.put(g.name, value);
            else {
                String v = fill(g);
                args.put(g.name, v == null ? "" : v);
            }
        }
        return Upnp.call(s, action, args);
    }

    private static String nextValue(Test t, String orig, Upnp.Action setter) {
        switch (t.kind) {
            case "bool":
                if (orig.equalsIgnoreCase("true")) return "false";
                if (orig.equalsIgnoreCase("false")) return "true";
                return orig.equals("1") ? "0" : "1";
            case "enum": {
                for (Upnp.Arg g : setter.inArgs()) {
                    if (g.name.equals(t.setArg)) {
                        for (String v : g.allowed) if (!v.equals(orig)) return v;
                    }
                }
                return orig;
            }
            default:
                try {
                    int v = Integer.parseInt(orig);
                    // Balance is in 1/256 dB and the stereo works in whole dB, so test with a 1 dB step.
                    int step = t.setter.equals("SetBalanceDB") ? 256 : 1;
                    return String.valueOf(v > 0 ? v - step : v + step);
                } catch (NumberFormatException e) {
                    return orig;
                }
        }
    }

    private static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equalsIgnoreCase(b)) return true;
        String x = a.equalsIgnoreCase("true") ? "1" : a.equalsIgnoreCase("false") ? "0" : a;
        String y = b.equalsIgnoreCase("true") ? "1" : b.equalsIgnoreCase("false") ? "0" : b;
        return x.equals(y);
    }

    // ------------------------------------------------------------------ report

    public String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Cambridge Connect Legacy – Stereo check report\n");
        sb.append("Date: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())).append('\n');
        sb.append("App: ").append(appVersion).append(" · Android ").append(Build.VERSION.RELEASE)
          .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Stereo: ").append(dev.friendlyName).append(" · ").append(dev.manufacturer).append(' ')
          .append(dev.modelName).append(' ').append(dev.modelNumber).append('\n');
        sb.append("Services: ").append(dev.services.size()).append("\n");
        sb.append("\n##### 1. READ TEST (").append(readSummary()).append(")\n");
        sb.append(readPart.length() == 0 ? "not run\n" : readPart);
        sb.append("\n##### 2. SOURCE TOUR (").append(tourCount).append(" sources)\n");
        sb.append(tourPart.length() == 0 ? "not run\n" : tourPart);
        sb.append("\n##### 3. SAFE CHANGE TEST (").append(changeSummary()).append(")\n");
        sb.append(changePart.length() == 0 ? "not run\n" : changePart);
        return sanitize(sb.toString(), dev.host());
    }

    /** Removes network addresses, MAC addresses and most of the serial number. */
    static String sanitize(String s, String host) {
        if (host != null && !host.isEmpty()) s = s.replace(host, "<stereo-ip>");
        s = s.replaceAll("\\b(?:10|127)\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b", "<lan-ip>");
        s = s.replaceAll("\\b192\\.168\\.\\d{1,3}\\.\\d{1,3}\\b", "<lan-ip>");
        s = s.replaceAll("\\b172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}\\b", "<lan-ip>");
        s = s.replaceAll("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b", "<mac>");
        Matcher m = Pattern.compile("(RetSerialNumberValue = )(\\S+)").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String v = m.group(2);
            String masked = v.length() <= 3 ? "***" : "***" + v.substring(v.length() - 3);
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + masked));
        }
        m.appendTail(out);
        s = out.toString();
        // The stereo's web page shows its unit ID (the serial); Spotify replies name the user's Spotify account.
        s = s.replaceAll("(Unit ID:\\s*)\\S+", "$1<hidden>");
        s = s.replaceAll("(?s)<spotify-source>.*?</spotify-source>", "<spotify-source><hidden></spotify-source>");
        return s;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        s = s.replace("\r", "");
        return s.length() > max ? s.substring(0, max) + " …[" + (s.length() - max) + " more chars]" : s;
    }
}
