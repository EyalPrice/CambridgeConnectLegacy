package io.github.eyalprice.ccl;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Minimal UPnP client: SSDP discovery, device/service description parsing and SOAP calls. */
public final class Upnp {

    private Upnp() {}

    public static final class Arg {
        public String name = "";
        public String direction = "in";
        public String related = "";
        public List<String> allowed = new ArrayList<>();
        public String range = "";
    }

    public static final class Action {
        public String name = "";
        public List<Arg> args = new ArrayList<>();

        public List<Arg> inArgs() {
            List<Arg> r = new ArrayList<>();
            for (Arg a : args) if ("in".equalsIgnoreCase(a.direction)) r.add(a);
            return r;
        }
    }

    public static final class Service {
        public String type = "";
        public String id = "";
        public String controlUrl = "";
        public String scpdUrl = "";
        public String error;
        public List<Action> actions = new ArrayList<>();

        public Action action(String name) {
            for (Action a : actions) if (a.name.equals(name)) return a;
            return null;
        }

        public String shortType() {
            String[] p = type.split(":");
            return p.length >= 2 ? p[p.length - 2] + ":" + p[p.length - 1] : type;
        }
    }

    public static final class Device {
        public String location = "";
        public String friendlyName = "";
        public String manufacturer = "";
        public String modelName = "";
        public String modelNumber = "";
        public List<Service> services = new ArrayList<>();

        public Service findService(String typeContains) {
            String needle = typeContains.toLowerCase(Locale.ROOT);
            for (Service s : services) if (s.type.toLowerCase(Locale.ROOT).contains(needle)) return s;
            return null;
        }

        public Service serviceByType(String type) {
            for (Service s : services) if (s.type.equals(type)) return s;
            return null;
        }

        public String host() {
            try { return new URL(location).getHost(); } catch (Exception e) { return location; }
        }
    }

    public static final class Found {
        public String location = "";
        public String server = "";
        public String name = "";
        public String model = "";
        /** True when the device offers the Cambridge (Reciva/UuVol) control service. */
        public boolean cambridge;
    }

    public static final class Result {
        public int httpCode;
        public String body = "";
        public Map<String, String> out = new LinkedHashMap<>();
        public boolean ok() { return httpCode >= 200 && httpCode < 300; }
    }

    // ---------------------------------------------------------------- discovery

    /**
     * Sends SSDP M-SEARCH requests and collects description URLs.
     * @param unicastIp null for normal multicast search, or an IP to ask one device directly.
     */
    public static List<Found> discover(Context ctx, String unicastIp, int timeoutMs) throws IOException {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wm != null) {
            lock = wm.createMulticastLock("ccl");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        Map<String, Found> out = new LinkedHashMap<>();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(400);
            InetAddress target = InetAddress.getByName(unicastIp == null ? "239.255.255.250" : unicastIp);
            String[] searchTargets = {"ssdp:all", "upnp:rootdevice", "urn:schemas-upnp-org:device:MediaRenderer:1"};
            for (int round = 0; round < 2; round++) {
                for (String st : searchTargets) {
                    String msg = "M-SEARCH * HTTP/1.1\r\n"
                            + "HOST: 239.255.255.250:1900\r\n"
                            + "MAN: \"ssdp:discover\"\r\n"
                            + "MX: 2\r\n"
                            + "ST: " + st + "\r\n\r\n";
                    byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
                    sock.send(new DatagramPacket(b, b.length, target, 1900));
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Map<String, String> h = parseHeaders(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                String loc = h.get("location");
                if (loc == null || loc.isEmpty() || out.containsKey(loc)) continue;
                Found f = new Found();
                f.location = loc;
                f.server = h.containsKey("server") ? h.get("server") : "";
                out.put(loc, f);
            }
        } finally {
            if (lock != null) lock.release();
        }
        return new ArrayList<>(out.values());
    }

    private static Map<String, String> parseHeaders(String resp) {
        Map<String, String> h = new HashMap<>();
        for (String line : resp.split("\r?\n")) {
            int i = line.indexOf(':');
            if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
        }
        return h;
    }

    /** Reads only the name and model of a device, used for the scan list. */
    public static void peek(Found f) {
        try {
            String xml = httpGet(f.location);
            String all = (xml + " " + f.server).toLowerCase(Locale.ROOT);
            f.cambridge = all.contains("uuvolcontrol") || all.contains("reciva") || all.contains("uuvol")
                    || all.contains("cambridge") || all.contains("minx") || all.contains("np30") || all.contains("stream magic");
            Document d = parseXml(xml);
            Element root = d.getDocumentElement();
            f.name = text(root, "friendlyName");
            String mfr = text(root, "manufacturer");
            String model = text(root, "modelName");
            f.model = (mfr + " " + model).trim();
        } catch (Exception e) {
            f.name = "(could not read)";
        }
    }

    // ---------------------------------------------------------------- description

    public static Device loadDevice(String location) throws Exception {
        Device dev = new Device();
        dev.location = location;
        Document d = parseXml(httpGet(location));
        Element root = d.getDocumentElement();
        dev.friendlyName = text(root, "friendlyName");
        dev.manufacturer = text(root, "manufacturer");
        dev.modelName = text(root, "modelName");
        dev.modelNumber = text(root, "modelNumber");

        String urlBase = text(root, "URLBase");
        URL base = new URL(urlBase.isEmpty() ? location : urlBase);

        NodeList svcs = root.getElementsByTagName("service");
        for (int i = 0; i < svcs.getLength(); i++) {
            Element e = (Element) svcs.item(i);
            Service s = new Service();
            s.type = text(e, "serviceType");
            s.id = text(e, "serviceId");
            s.controlUrl = new URL(base, text(e, "controlURL")).toString();
            s.scpdUrl = new URL(base, text(e, "SCPDURL")).toString();
            try {
                loadScpd(s);
            } catch (Exception ex) {
                s.error = ex.toString();
            }
            dev.services.add(s);
        }
        return dev;
    }

    private static void loadScpd(Service s) throws Exception {
        Document d = parseXml(httpGet(s.scpdUrl));
        Element root = d.getDocumentElement();

        Map<String, Arg> varInfo = new HashMap<>();
        NodeList vars = root.getElementsByTagName("stateVariable");
        for (int i = 0; i < vars.getLength(); i++) {
            Element v = (Element) vars.item(i);
            Arg info = new Arg();
            NodeList allowed = v.getElementsByTagName("allowedValue");
            for (int j = 0; j < allowed.getLength(); j++) info.allowed.add(allowed.item(j).getTextContent().trim());
            NodeList range = v.getElementsByTagName("allowedValueRange");
            if (range.getLength() > 0) {
                Element r = (Element) range.item(0);
                info.range = text(r, "minimum") + ".." + text(r, "maximum");
            }
            varInfo.put(text(v, "name"), info);
        }

        NodeList acts = root.getElementsByTagName("action");
        for (int i = 0; i < acts.getLength(); i++) {
            Element a = (Element) acts.item(i);
            Action act = new Action();
            act.name = directText(a, "name");
            NodeList args = a.getElementsByTagName("argument");
            for (int j = 0; j < args.getLength(); j++) {
                Element ae = (Element) args.item(j);
                Arg arg = new Arg();
                arg.name = text(ae, "name");
                arg.direction = text(ae, "direction");
                arg.related = text(ae, "relatedStateVariable");
                Arg info = varInfo.get(arg.related);
                if (info != null) {
                    arg.allowed = info.allowed;
                    arg.range = info.range;
                }
                act.args.add(arg);
            }
            s.actions.add(act);
        }
    }

    // ---------------------------------------------------------------- SOAP

    public static Result call(Service s, String action, Map<String, String> args) throws IOException {
        return call(s, action, args, 8000);
    }

    public static Result call(Service s, String action, Map<String, String> args, int readTimeoutMs) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            .append("<u:").append(action).append(" xmlns:u=\"").append(esc(s.type)).append("\">");
        for (Map.Entry<String, String> e : args.entrySet()) {
            body.append('<').append(e.getKey()).append('>')
                .append(esc(e.getValue()))
                .append("</").append(e.getKey()).append('>');
        }
        body.append("</u:").append(action).append("></s:Body></s:Envelope>");

        HttpURLConnection c = (HttpURLConnection) new URL(s.controlUrl).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPACTION", "\"" + s.type + "#" + action + "\"");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
        }
        Result r = new Result();
        r.httpCode = c.getResponseCode();
        InputStream is = r.httpCode >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = is == null ? "" : readAll(is);
        c.disconnect();

        try {
            Document d = parseXml(r.body);
            NodeList all = d.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Node n = all.item(i);
                if (localName(n.getNodeName()).equals(action + "Response")) {
                    NodeList kids = n.getChildNodes();
                    for (int k = 0; k < kids.getLength(); k++) {
                        Node kid = kids.item(k);
                        if (kid.getNodeType() == Node.ELEMENT_NODE) {
                            r.out.put(localName(kid.getNodeName()), kid.getTextContent());
                        }
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
            // body is still shown raw
        }
        return r;
    }

    // ---------------------------------------------------------------- helpers

    static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + " for " + url);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                bos.write(b, 0, n);
                if (bos.size() > 2_000_000) break; // safety limit
            }
            return bos.toString("UTF-8");
        }
    }

    static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        try { f.setExpandEntityReferences(false); } catch (Exception ignored) { }
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.trim().getBytes(StandardCharsets.UTF_8)));
    }

    private static String localName(String n) {
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    /** First descendant element with this tag, text trimmed. */
    static String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? "" : nl.item(0).getTextContent().trim();
    }

    /** Direct child element text only (avoids picking up argument names inside an action). */
    static String directText(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tag)) return n.getTextContent().trim();
        }
        return "";
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
