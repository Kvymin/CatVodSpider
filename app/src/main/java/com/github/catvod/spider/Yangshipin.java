package com.github.catvod.spider;

import static com.github.catvod.spider.YangshipinData.*;

import android.content.Context;
import android.util.Base64;

import com.fongmi.quickjs.WasmRuntime;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.Net;
import com.github.catvod.net.NetOptions;
import com.github.catvod.utils.CallTimeout;
import com.github.catvod.utils.Crypto;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.HttpUrl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Yangshipin extends Spider {

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEYGEN_CACHE = "keygen:java:v1";
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Set<Net.StreamResult> readers = ConcurrentHashMap.newKeySet();
    private final Set<Net> sessions = ConcurrentHashMap.newKeySet();
    private final ReentrantLock tvLock = new ReentrantLock();
    private WasmRuntime wasm;
    private String snapshot;
    private volatile String tvPrefix;
    private YangshipinTv tv;

    static final class ProxyException extends Exception {

        final int status;

        ProxyException(int status, String message) {
            super(message);
            this.status = status;
        }

        ProxyException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }
    }

    record Reply(int code, Map<String, Object> headers, byte[] body) {}

    record Playlist(String text, String url) {}

    record SourceState(String key, String value) {}

    @Override
    public void init(Context context, String extend) {
        snapshot = extend == null ? "" : extend.trim();
        if (!snapshot.isEmpty()) httpUrl(snapshot);
        wasm = new WasmRuntime();
        invalidateTv();
    }

    @Override
    public String liveContent(String url) throws Exception {
        JsonArray channels = loadChannels(url);
        Map<String, JsonArray> groups = new LinkedHashMap<>();
        int number = 0;
        for (JsonElement value : channels) {
            JsonObject channel = value.getAsJsonObject();
            String group = mapped(GROUP_NAMES, str(channel, "type"), "其他頻道");
            String name = str(channel, "name"),
                    pid = str(channel, "pid"),
                    cnlid = str(channel, "cnlid");
            String logo = mapped(LOGO_NAMES, name, name.replaceAll("频道$", ""));
            JsonObject item =
                    json(
                            "name",
                            name,
                            "tvgName",
                            mapped(XMLTV_NAMES, name, name),
                            "tvgId",
                            pid,
                            "number",
                            String.format(Locale.ROOT, "%02d", ++number),
                            "format",
                            HLS_MIME,
                            "ua",
                            USER_AGENT,
                            "logo",
                            logo.isEmpty()
                                    ? str(channel, "logo")
                                    : "https://epg.112114.xyz/logo/" + encode(logo) + ".png",
                            "urls",
                            List.of(
                                    proxyUrl(
                                            Map.of(
                                                    "type",
                                                    "auto",
                                                    "pid",
                                                    pid,
                                                    "cnlid",
                                                    cnlid,
                                                    "defn",
                                                    bool(channel, "vip") ? "shd" : "fhd"))));
            String epg =
                    mapped(
                            EPG_IDS,
                            name,
                            name.matches("CCTV(?:[1-9]|1[0-7])")
                                    ? name.toLowerCase(Locale.ROOT)
                                    : "");
            if (!epg.isEmpty())
                item.addProperty(
                        "epg", proxyUrl(Map.of("type", "epg", "id", epg)) + "&date={date}");
            groups.computeIfAbsent(group, ignored -> new JsonArray()).add(item);
        }
        JsonArray result = new JsonArray();
        groups.forEach((name, items) -> result.add(json("name", name, "channel", items)));
        active();
        return GSON.toJson(result);
    }

    JsonArray loadChannels(String url) throws Exception {
        try {
            return officialChannels(url);
        } catch (Exception error) {
            cancellation(error);
            if (snapshot == null || snapshot.isEmpty() || destroyed.get()) throw error;
            try {
                JsonArray channels = snapshotChannels();
                SpiderDebug.log("央視頻 catalog fallback: " + safe(error));
                return channels;
            } catch (Exception backup) {
                cancellation(backup);
                if (error != backup) error.addSuppressed(backup);
                throw error;
            }
        }
    }

    private JsonArray officialChannels(String url) throws Exception {
        String stamp = Long.toString(System.currentTimeMillis() / 5000);
        String navigationUrl =
                query(url == null || url.isEmpty() ? NAVIGATION_URL : url, json("_", stamp));
        Reply navigation =
                request(
                        net,
                        navigationUrl,
                        catalogOptions(),
                        "頻道清單",
                        deadline(8000),
                        4 * 1024 * 1024);
        String feed = YangshipinProto.navigation(navigation.body());
        Reply page =
                request(
                        net,
                        PAGE_URL + encode(feed) + "?" + stamp,
                        catalogOptions(),
                        "頻道清單",
                        deadline(8000),
                        8 * 1024 * 1024);
        return YangshipinProto.channels(page.body());
    }

    private JsonArray snapshotChannels() throws Exception {
        JsonObject value = requestJson(net, snapshot, new JsonObject(), "頻道備援", deadline(8000));
        JsonArray channels = new JsonArray();
        for (JsonElement item : array(value, "channels")) {
            if (!item.isJsonObject()) throw new ProxyException(502, "invalid channel snapshot");
            JsonObject channel = item.getAsJsonObject().deepCopy();
            JsonElement label = channel.get("name");
            String name =
                    label != null
                                    && label.isJsonPrimitive()
                                    && label.getAsJsonPrimitive().isString()
                            ? label.getAsString().trim()
                            : "";
            String pid = snapshotId(channel.get("pid")), cnlid = snapshotId(channel.get("cnlid"));
            if (name.isEmpty() || !channelId(pid) || !channelId(cnlid))
                throw new ProxyException(502, "invalid channel snapshot");
            channel.addProperty("name", name);
            channel.addProperty("pid", pid);
            channel.addProperty("cnlid", cnlid);
            JsonElement logo = channel.get("logo");
            if (logo == null || !logo.isJsonPrimitive() || !logo.getAsJsonPrimitive().isString())
                channel.addProperty("logo", "");
            channels.add(channel);
        }
        if (channels.isEmpty()) throw new ProxyException(502, "invalid channel snapshot");
        return channels;
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        try {
            active();
            String type = params.getOrDefault("type", "web");
            if (type.equals("epg"))
                return new Object[] {
                    200, "application/json", body(epg(params.get("id"), params.get("date")))
                };
            if (type.equals("auto")) return proxyAuto(params);
            if (!List.of("web", "app", "tv").contains(type))
                throw new ProxyException(400, "invalid playback source");
            validateChannel(params);
            long deadline = deadline(25000);
            JsonObject entry = resourceEntry(params, deadline);
            try {
                return proxyEntry(entry, params, deadline, false);
            } catch (Exception error) {
                failSource(params, type, entry, error);
                throw error;
            }
        } catch (Exception error) {
            cancellation(error);
            return new Object[] {
                error instanceof ProxyException failure ? failure.status : 502,
                "text/plain; charset=utf-8",
                body("央視頻: " + safe(error))
            };
        }
    }

    private Object[] proxyAuto(Map<String, String> params) throws Exception {
        validateChannel(params);
        if (params.containsKey("url")
                || params.containsKey("generation")
                || !params.getOrDefault("kind", "playlist").equals("playlist"))
            throw new ProxyException(400, "invalid automatic playback request");
        long deadline = deadline(25000);
        List<String> sources = new ArrayList<>();
        if (TV_CHANNELS.has(params.get("pid"))) sources.add("tv");
        sources.add("app");
        sources.add("web");
        String key = autoKey(params);
        JsonObject choice =
                cached(key, AUTO_CHOICE_MS, () -> json("source", sources.get(0), "generation", ""));
        String preferred = str(choice, "source");
        if (sources.remove(preferred)) sources.add(0, preferred);
        List<String> errors = new ArrayList<>();
        for (String source : sources) {
            active();
            JsonObject failure =
                    cached(key + ":" + source, AUTO_FAILURE_MS, () -> json("message", ""));
            String current = net.peek(sourceKey(source, params));
            if (!str(failure, "message").isEmpty()
                    && (current == null
                            || generation(current).equals(str(failure, "generation")))) {
                errors.add(source + ": " + str(failure, "message"));
                continue;
            }
            remaining(deadline, "自動播放取址");
            long attempt = Math.min(deadline, deadline(AUTO_SOURCE_MS));
            JsonObject entry = null;
            SourceState before = sourceState(source, params, refresh(params));
            try {
                entry = liveEntry(source, params, attempt, before);
                proxyEntry(entry, params, attempt, true);
                JsonObject selected =
                        json("source", source, "generation", str(entry, "generation"));
                if (!selected.equals(choice)) {
                    net.clearCache(key);
                    cached(key, AUTO_CHOICE_MS, () -> selected);
                }
                return new Object[] {
                    302,
                    "text/plain",
                    body(""),
                    Map.of("Location", proxyUrl(target(entry)), "Cache-Control", "no-store")
                };
            } catch (Exception error) {
                failSource(params, source, entry, before, error);
                errors.add(source + ": " + safe(error));
            }
        }
        throw new ProxyException(502, "所有播放來源均失敗（" + String.join("；", errors) + "）");
    }

    private JsonObject resourceEntry(Map<String, String> params, long deadline) throws Exception {
        String generation = params.get("generation");
        if (generation == null)
            return liveEntry(params.getOrDefault("type", "web"), params, deadline, refresh(params));
        return cached(
                entryKey(params),
                PLAY_SESSION_MS,
                () -> {
                    JsonObject entry = liveEntry(params.get("type"), params, deadline, false);
                    if (!str(entry, "generation").equals(generation))
                        throw new ProxyException(410, "播放來源已更新，請重新播放");
                    return entry;
                });
    }

    private JsonObject liveEntry(
            String source, Map<String, String> params, long deadline, boolean refresh)
            throws Exception {
        return liveEntry(source, params, deadline, sourceState(source, params, refresh));
    }

    private String sourceKey(String source, Map<String, String> params) {
        return source.equals("tv")
                ? tvPrefix + params.get("pid")
                : source
                        + "play:"
                        + params.get("pid")
                        + ":"
                        + params.get("cnlid")
                        + ":"
                        + params.getOrDefault("defn", "fhd");
    }

    private SourceState sourceState(String source, Map<String, String> params, boolean refresh)
            throws Exception {
        active();
        String key = sourceKey(source, params);
        if (refresh) net.clearCache(key);
        return new SourceState(key, net.peek(key));
    }

    private JsonObject liveEntry(
            String source, Map<String, String> params, long deadline, SourceState before)
            throws Exception {
        active();
        String pid = params.get("pid"),
                cnlid = params.get("cnlid"),
                definition = params.getOrDefault("defn", "fhd");
        String key = before.key();
        JsonObject entry =
                cached(
                        key,
                        60000,
                        () -> {
                            if (source.equals("app"))
                                return YangshipinApp.resolve(
                                        this, pid, cnlid, definition, deadline);
                            if (source.equals("tv"))
                                return withTv(
                                        deadline,
                                        () -> {
                                            if (!TV_CHANNELS.has(pid))
                                                throw new ProxyException(
                                                        400, "invalid TV channel id");
                                            if (tv == null) tv = new YangshipinTv(this);
                                            try {
                                                return tv.resolve(
                                                        TV_CHANNELS.get(pid).getAsString(),
                                                        deadline);
                                            } catch (Exception error) {
                                                invalidateTv();
                                                throw error;
                                            }
                                        });
                            return json(
                                    "url",
                                    resolveWeb(pid, cnlid, deadline),
                                    "headers",
                                    playerHeaders());
                        });
        entry.addProperty("cacheKey", key);
        entry.addProperty("source", source);
        entry.addProperty("pid", pid);
        entry.addProperty("cnlid", cnlid);
        entry.addProperty("defn", definition);
        entry.addProperty("generation", generation(entry));
        return entry;
    }

    private static String generation(JsonObject entry) throws ProxyException {
        return Crypto.md5(str(entry, "url") + "\n" + canonical(object(entry, "headers")));
    }

    private static String generation(String cached) throws ProxyException {
        return cached == null ? "" : generation(JsonParser.parseString(cached).getAsJsonObject());
    }

    private void failSource(
            Map<String, String> params, String source, JsonObject entry, Exception error)
            throws Exception {
        failSource(params, source, entry, null, error);
    }

    private void failSource(
            Map<String, String> params,
            String source,
            JsonObject entry,
            SourceState before,
            Exception error)
            throws Exception {
        cancellation(error);
        active();
        String failedGeneration;
        if (entry != null) {
            String key = str(entry, "cacheKey"), current = net.peek(key);
            failedGeneration = str(entry, "generation");
            if (current == null || !generation(current).equals(failedGeneration)) return;
            before = new SourceState(key, current);
        } else failedGeneration = generation(before.value());
        if (!net.invalidateCache(before.key(), before.value())) return;
        String key = autoKey(params) + ":" + source;
        net.clearCache(key);
        cached(
                key,
                AUTO_FAILURE_MS,
                () -> json("message", safe(error), "generation", failedGeneration));
        SpiderDebug.log("央視頻 " + source + ": " + safe(error));
    }

    private Object[] proxyEntry(
            JsonObject entry, Map<String, String> params, long deadline, boolean probe)
            throws Exception {
        String url = params.getOrDefault("url", str(entry, "url"));
        if (!playbackUrl(url)) throw new ProxyException(400, "invalid playback URL");
        String kind = params.getOrDefault("kind", "playlist");
        if (!List.of("playlist", "segment").contains(kind))
            throw new ProxyException(400, "invalid playback resource type");
        JsonObject headers = object(entry, "headers").deepCopy();
        if (kind.equals("segment")) {
            if (params.containsKey("range")) headers.addProperty("Range", params.get("range"));
            Net.StreamResult stream = open(net, url, json("headers", headers), "直播切片", deadline);
            try {
                active();
                if (stream.code >= 400)
                    failSource(
                            params,
                            str(entry, "source"),
                            entry,
                            new ProxyException(stream.code, "直播切片: HTTP " + stream.code));
                readers.remove(stream);
                return new Object[] {
                    stream.code, stream.contentType, stream, proxyHeaders(stream.headers)
                };
            } catch (Exception | Error error) {
                try {
                    closeReader(stream);
                } catch (RuntimeException | Error closing) {
                    if (error != closing) error.addSuppressed(closing);
                }
                throw error;
            }
        }
        if (str(entry, "source").equals("tv"))
            withTv(
                    deadline,
                    () -> {
                        try {
                            if (tv == null) throw new ProxyException(503, "播放服務已關閉");
                            tv.heartbeat(deadline);
                            return null;
                        } catch (Exception error) {
                            invalidateTv();
                            throw error;
                        }
                    });
        if (refresh(params)) headers.addProperty("Cache-Control", "no-cache");
        Playlist playlist = playlist(url, headers, deadline);
        if (probe) probe(playlist, headers, deadline);
        Map<String, String> target = target(entry);
        cached(entryKey(target), PLAY_SESSION_MS, () -> entry);
        return new Object[] {
            200,
            HLS_MIME,
            body(rewritePlaylist(playlist.text(), playlist.url(), target)),
            Map.of("Cache-Control", "no-store")
        };
    }

    private static ByteArrayInputStream body(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> proxyHeaders(Map<String, Object> source) {
        Map<String, String> headers = new LinkedHashMap<>();
        source.forEach(
                (name, value) ->
                        headers.put(
                                name,
                                value instanceof List<?> values
                                        ? String.join(
                                                ", ",
                                                values.stream().map(Object::toString).toList())
                                        : value.toString()));
        return headers;
    }

    Playlist playlist(String url, JsonObject headers, long deadline) throws Exception {
        for (int count = 0; count < 6; count++) {
            if (!playbackUrl(url)) throw new ProxyException(502, "直播 HLS: invalid redirect URL");
            Reply reply =
                    exchange(
                            net,
                            url,
                            json("headers", headers, "redirect", 0),
                            "直播 HLS",
                            deadline,
                            4 * 1024 * 1024);
            if (List.of(301, 302, 303, 307, 308).contains(reply.code())) {
                String location = header(reply.headers(), "location");
                if (location.isEmpty())
                    throw new ProxyException(502, "直播 HLS: missing redirect location");
                url = resolveUrl(url, location);
                continue;
            }
            status(reply.code(), "直播 HLS");
            String text = utf8(reply.body());
            if (reply.code() != 200 || !text.stripLeading().startsWith("#EXTM3U"))
                throw new ProxyException(502, "直播 HLS: invalid playlist");
            return new Playlist(text, url);
        }
        throw new ProxyException(502, "直播 HLS: too many redirects");
    }

    private void probe(Playlist playlist, JsonObject headers, long deadline) throws Exception {
        for (int count = 0; count < 3; count++) {
            boolean variant = false;
            String uri = null;
            for (String line : playlist.text().split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("#EXT-X-STREAM-INF:")) variant = true;
                else if (!line.isEmpty() && !line.startsWith("#")) {
                    uri = line;
                    break;
                }
            }
            if (uri == null) throw new ProxyException(502, "直播 HLS: missing media URI");
            String url = resolveUrl(playlist.url(), uri);
            if (variant || httpUrl(url).encodedPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
                playlist = playlist(url, headers, deadline);
                continue;
            }
            Matcher resources =
                    Pattern.compile("#EXT-X-(?:KEY|MAP):[^\\r\\n]*?URI=\"([^\"]+)\"")
                            .matcher(playlist.text());
            while (resources.find())
                probeMedia(resolveUrl(playlist.url(), resources.group(1)), headers, deadline);
            probeMedia(url, headers, deadline);
            return;
        }
        throw new ProxyException(502, "直播 HLS: too many nested playlists");
    }

    private void probeMedia(String url, JsonObject headers, long deadline) throws Exception {
        JsonObject values = headers.deepCopy();
        values.addProperty("Range", "bytes=0-1023");
        Net.StreamResult stream = open(net, url, json("headers", values), "直播切片", deadline);
        try (stream) {
            status(stream.code, "直播切片驗證");
            if (stream.code != 200 && stream.code != 206)
                throw new ProxyException(502, "直播切片驗證: HTTP " + stream.code);
            if (stream.contentType
                    .toLowerCase(Locale.ROOT)
                    .matches("^(text/html|application/json).*"))
                throw new ProxyException(502, "直播切片驗證: unexpected content type");
            if (stream.read() < 0) throw new ProxyException(502, "直播切片: empty body");
            active();
            remaining(deadline, "直播切片驗證");
        } finally {
            readers.remove(stream);
        }
    }

    String rewritePlaylist(String text, String base, Map<String, String> target) throws Exception {
        List<String> lines = new ArrayList<>();
        boolean variant = false;
        for (String line : text.split("\\r?\\n", -1)) {
            String stripped = line.trim();
            if (stripped.startsWith("#")) {
                String kind =
                        stripped.matches("^#EXT-X-(MEDIA|I-FRAME-STREAM-INF|RENDITION-REPORT):.*")
                                ? "playlist"
                                : "segment";
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer output = new StringBuffer();
                while (matcher.find())
                    matcher.appendReplacement(
                            output,
                            Matcher.quoteReplacement(
                                    "URI=\""
                                            + resourceProxy(base, matcher.group(1), kind, target)
                                            + "\""));
                matcher.appendTail(output);
                line = output.toString();
                if (stripped.startsWith("#EXT-X-STREAM-INF:")) variant = true;
            } else if (!stripped.isEmpty()) {
                String url = resolveUrl(base, stripped);
                line =
                        resourceProxy(
                                base,
                                stripped,
                                variant
                                                || httpUrl(url)
                                                        .encodedPath()
                                                        .toLowerCase(Locale.ROOT)
                                                        .endsWith(".m3u8")
                                        ? "playlist"
                                        : "segment",
                                target);
                variant = false;
            }
            lines.add(line);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty())
            lines.remove(lines.size() - 1);
        return String.join("\n", lines) + "\n";
    }

    private String resourceProxy(String base, String value, String kind, Map<String, String> target)
            throws Exception {
        Map<String, String> params = new HashMap<>(target);
        params.put("kind", kind);
        params.put("url", resolveUrl(base, value));
        return proxyUrl(params);
    }

    private String epg(String id, String date) throws Exception {
        if (id == null
                || !id.matches("[a-z0-9]+")
                || date == null
                || !date.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new ProxyException(400, "invalid epg request");
        try {
            SimpleDateFormat format = dateFormat("yyyy-MM-dd");
            if (!format.format(format.parse(date)).equals(date))
                throw new IllegalArgumentException();
        } catch (java.text.ParseException | RuntimeException error) {
            throw new ProxyException(400, "invalid date", error);
        }
        JsonObject result =
                cached(
                        "epg:" + id + ":" + date,
                        3600000,
                        () -> {
                            JsonObject response =
                                    requestJson(
                                            net,
                                            EPG_URL,
                                            json(
                                                    "params",
                                                            json(
                                                                    "c",
                                                                    id,
                                                                    "serviceId",
                                                                    "tvcctv",
                                                                    "d",
                                                                    date.replace("-", "")),
                                                    "headers",
                                                            json(
                                                                    "Referer",
                                                                    "https://tv.cctv.com/",
                                                                    "User-Agent",
                                                                    USER_AGENT)),
                                            "節目表",
                                            deadline(8000));
                            JsonArray rows = array(object(object(response, "data"), id), "list"),
                                    items = new JsonArray();
                            for (JsonElement value : rows) {
                                JsonObject row = value.getAsJsonObject();
                                String title = str(row, "title").trim();
                                String start = epgTime(str(row, "startTime")),
                                        end = epgTime(str(row, "endTime"));
                                if (!title.isEmpty() && !start.isEmpty() && !end.isEmpty())
                                    items.add(json("title", title, "start", start, "end", end));
                            }
                            return json("date", date, "epg_data", items);
                        });
        return GSON.toJson(result);
    }

    private String resolveWeb(String pid, String cnlid, long deadline) throws Exception {
        String guid = Long.toString(System.currentTimeMillis(), 36) + "_" + randomText(11);
        Net session = net.session();
        sessions.add(session);
        try (session;
                YangshipinKeygen keygen = keygen(deadline)) {
            JsonObject cookies =
                    json(
                            "guid",
                            guid,
                            "versionName",
                            "99.99.99",
                            "versionCode",
                            "999999",
                            "vplatform",
                            "109",
                            "platformVersion",
                            "Chrome",
                            "deviceModel",
                            "148",
                            "newLogin",
                            "1",
                            "nseqId",
                            "1");
            for (var cookie : cookies.entrySet())
                if (!session.setCookie(
                        HOST,
                        cookie.getKey()
                                + "="
                                + cookie.getValue().getAsString()
                                + "; Domain=yangshipin.cn; Path=/"))
                    throw new ProxyException(502, "invalid player cookie");
            String timestamp = Long.toString(Math.round(System.currentTimeMillis() / 1000.0));
            JsonObject authParams =
                    json("pid", pid, "guid", guid, "appid", "ysp_pc", "rand_str", randomText(10));
            authParams.addProperty("signature", signature(authParams, AUTH_SALT));
            JsonObject auth =
                    webJson(
                            session,
                            PLAYER_URL + "auth",
                            json(
                                    "method",
                                    "POST",
                                    "data",
                                    authParams,
                                    "postType",
                                    "form",
                                    "headers",
                                    json(
                                            "Content-Type",
                                            "application/x-www-form-urlencoded;charset=UTF-8")),
                            "播放授權",
                            deadline);
            JsonObject authData = object(auth, "data");
            if (!str(auth, "code").equals("0")
                    || str(authData, "token").isBlank()
                    || !str(authData, "ts").matches("[1-9][0-9]*"))
                throw new ProxyException(502, "播放授權: player auth failed");
            JsonObject params =
                    json(
                            "cnlid",
                            cnlid,
                            "livepid",
                            pid,
                            "stream",
                            "2",
                            "guid",
                            guid,
                            "cKey",
                            webCkey(cnlid, timestamp, guid),
                            "adjust",
                            1,
                            "appVer",
                            APP_VERSION,
                            "app_version",
                            APP_VERSION,
                            "channel",
                            "ysp_tx",
                            "defn",
                            "fhd",
                            "platform",
                            PLATFORM,
                            "sphttps",
                            "1",
                            "cmd",
                            "2",
                            "encryptVer",
                            "8.1",
                            "dtype",
                            "1",
                            "devid",
                            "devid",
                            "otype",
                            "ojson");
            JsonObject headers = sdkHeaders(session, guid, params, deadline, keygen);
            headers.addProperty("yspPlayerToken", str(authData, "token"));
            headers.addProperty("yspticket", ticket(pid, str(authData, "ts"), guid));
            params.addProperty("rand_str", randomText(10));
            params.addProperty("signature", signature(params, REQUEST_SALT));
            JsonObject result =
                    webJson(
                            session,
                            PLAYER_URL + "get_live_info",
                            json("method", "POST", "data", params, "headers", headers),
                            "取得播放資訊",
                            deadline);
            if (!str(result, "code").equals("0"))
                throw new ProxyException(502, "取得播放資訊: " + str(result, "msg"));
            JsonObject data = object(result, "data");
            if (data.has("iretcode") && !str(data, "iretcode").equals("0"))
                throw new ProxyException(502, "取得播放資訊: " + str(data, "errinfo"));
            List<String> urls = new ArrayList<>();
            if (data.has("backurl_list"))
                for (JsonElement value : array(data, "backurl_list"))
                    urls.add(str(value.getAsJsonObject(), "url"));
            urls.add(str(data, "playurl"));
            for (String url : urls) if (playbackUrl(url)) return mediaUrl(url, data);
            throw new ProxyException(502, "取得播放資訊: missing hls manifest");
        } finally {
            sessions.remove(session);
        }
    }

    private JsonObject sdkHeaders(
            Net session, String guid, JsonObject params, long deadline, YangshipinKeygen keygen)
            throws Exception {
        String input = Crypto.md5(canonical(params)),
                sequence = "1",
                requestId = "999999" + randomText(10) + System.currentTimeMillis();
        String composite = input + "-" + guid + "-" + sequence + "-" + requestId;
        Map<String, String> state =
                new HashMap<>(
                        Map.of(
                                "guid",
                                guid,
                                "token",
                                "",
                                "input",
                                "",
                                "yspappid",
                                YSP_APP_ID,
                                "ts",
                                Long.toString(System.currentTimeMillis()),
                                "version",
                                "v1",
                                "query",
                                "object"));
        JsonObject query =
                json(
                        "yspappid",
                        YSP_APP_ID,
                        "guid",
                        guid,
                        "vappid",
                        "59306155",
                        "vsecret",
                        OPEN_TOKEN_SECRET,
                        "raw",
                        1,
                        "version",
                        "v1",
                        "ts",
                        state.get("ts"),
                        "rnd",
                        keygen.call("get_token_rnd", state, deadline));
        JsonObject result =
                webJson(session, OPEN_TOKEN_URL, json("params", query), "簽名授權", deadline);
        String token = str(object(result, "data"), "token");
        if (token.isBlank()) throw new ProxyException(502, "簽名授權: open token failed");
        state.put("token", token);
        state.put("input", composite);
        return json(
                "Content-Type",
                "application/json;charset=UTF-8",
                "yspsdkinput",
                input,
                "yspsdksign",
                keygen.call("get_signature", state, deadline) + "-" + composite,
                "seqId",
                sequence,
                "request-id",
                requestId,
                "yspappid",
                YSP_APP_ID);
    }

    YangshipinKeygen keygen(long deadline) throws Exception {
        JsonObject record = cached(KEYGEN_CACHE, KEYGEN_TTL, true, () -> updateKeygen(deadline));
        return new YangshipinKeygen(wasm, keygenBytes(record), deadline);
    }

    JsonObject updateKeygen(long deadline) throws Exception {
        String previousText = net.peek(KEYGEN_CACHE);
        JsonObject previous =
                previousText == null
                        ? new JsonObject()
                        : JsonParser.parseString(previousText).getAsJsonObject();
        JsonObject headers = playerHeaders();
        if (!str(previous, "etag").isEmpty())
            headers.addProperty("If-None-Match", str(previous, "etag"));
        if (!str(previous, "modified").isEmpty())
            headers.addProperty("If-Modified-Since", str(previous, "modified"));
        Reply reply =
                exchange(net, KEYGEN_URL, json("headers", headers), "簽名檔更新", deadline, 100000);
        byte[] bytes;
        if (reply.code() == 304) bytes = keygenBytes(previous);
        else {
            status(reply.code(), "簽名檔更新");
            bytes = reply.body();
        }
        validateKeygenBytes(bytes);
        try (YangshipinKeygen candidate = new YangshipinKeygen(wasm, bytes, deadline)) {
            remaining(deadline, "簽名檔更新");
        }
        String etag = header(reply.headers(), "etag"),
                modified = header(reply.headers(), "last-modified");
        return json(
                "bytes",
                Base64.encodeToString(bytes, Base64.NO_WRAP),
                "etag",
                etag.isEmpty() && reply.code() == 304 ? str(previous, "etag") : etag,
                "modified",
                modified.isEmpty() && reply.code() == 304 ? str(previous, "modified") : modified);
    }

    private static byte[] keygenBytes(JsonObject record) throws ProxyException {
        try {
            byte[] bytes = Base64.decode(str(record, "bytes"), Base64.DEFAULT);
            validateKeygenBytes(bytes);
            return bytes;
        } catch (IllegalArgumentException error) {
            throw new ProxyException(502, "簽名初始化: invalid cached wasm", error);
        }
    }

    private static void validateKeygenBytes(byte[] bytes) throws ProxyException {
        if (bytes.length < 20000
                || bytes.length > 100000
                || !Arrays.equals(
                        Arrays.copyOf(bytes, 8), new byte[] {0, 97, 115, 109, 1, 0, 0, 0}))
            throw new ProxyException(502, "簽名檔更新: invalid official wasm");
    }

    JsonObject cached(String key, long ttl, Callable<JsonObject> loader) throws Exception {
        return cached(key, ttl, false, loader);
    }

    private JsonObject cached(String key, long ttl, boolean persist, Callable<JsonObject> loader)
            throws Exception {
        active();
        String text =
                net.cached(
                        key,
                        GSON.toJson(json("ttl", ttl, "stale", false, "persist", persist)),
                        () -> {
                            active();
                            JsonObject value = loader.call();
                            active();
                            return GSON.toJson(value);
                        });
        active();
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private JsonObject webJson(
            Net client, String url, JsonObject options, String stage, long deadline)
            throws Exception {
        JsonObject headers = playerHeaders();
        if (options.has("headers"))
            object(options, "headers")
                    .entrySet()
                    .forEach(value -> headers.add(value.getKey(), value.getValue()));
        options = options.deepCopy();
        options.add("headers", headers);
        return requestJson(client, url, options, stage, deadline);
    }

    JsonObject requestJson(Net client, String url, JsonObject options, String stage, long deadline)
            throws Exception {
        return JsonParser.parseString(
                        utf8(
                                request(client, url, options, stage, deadline, 4 * 1024 * 1024)
                                        .body()))
                .getAsJsonObject();
    }

    Reply request(
            Net client, String url, JsonObject options, String stage, long deadline, int limit)
            throws Exception {
        Reply reply = exchange(client, url, options, stage, deadline, limit);
        status(reply.code(), stage);
        return reply;
    }

    Reply exchange(
            Net client, String url, JsonObject options, String stage, long deadline, int limit)
            throws Exception {
        Net.StreamResult stream = open(client, url, options, stage, deadline);
        try (stream;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536];
            for (int count; (count = stream.read(buffer)) >= 0; ) {
                remaining(deadline, stage);
                if (count > limit - output.size()) {
                    status(stream.code, stage);
                    throw new ProxyException(502, stage + ": response too large");
                }
                output.write(buffer, 0, count);
            }
            active();
            return new Reply(stream.code, stream.headers, output.toByteArray());
        } finally {
            readers.remove(stream);
        }
    }

    Net.StreamResult open(Net client, String url, JsonObject options, String stage, long deadline)
            throws Exception {
        active();
        options = options.deepCopy();
        options.addProperty("timeout", remaining(deadline, stage));
        options.addProperty("callTimeout", Math.max(1, deadline - System.currentTimeMillis()));
        CompletableFuture<Net.StreamResult> future =
                client.requestStreamAsync(
                        httpUrl(url).toString(), NetOptions.from(options.toString()));
        try {
            pending.add(future);
            CallTimeout.trackCurrent(future);
            future.whenComplete((value, error) -> pending.remove(future));
            active();
            Net.StreamResult stream = future.get(remaining(deadline, stage), TimeUnit.MILLISECONDS);
            readers.add(stream);
            active();
            return stream;
        } catch (Exception | Error error) {
            Throwable failure =
                    error instanceof ExecutionException && error.getCause() != null
                            ? error.getCause()
                            : error;
            future.cancel(true);
            pending.remove(future);
            closeUnclaimed(future, failure);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (error instanceof TimeoutException)
                throw new ProxyException(504, stage + ": request timed out", error);
            if (failure instanceof Exception cause) throw cause;
            if (failure instanceof Error cause) throw cause;
            throw new IllegalStateException(failure);
        }
    }

    private void closeUnclaimed(CompletableFuture<Net.StreamResult> future, Throwable failure) {
        future.thenAccept(
                stream -> {
                    try {
                        closeReader(stream);
                    } catch (RuntimeException | Error closing) {
                        if (failure != closing) failure.addSuppressed(closing);
                    }
                });
    }

    static JsonObject playerHeaders() {
        return json(
                "Origin",
                HOST,
                "Referer",
                HOST + "/",
                "User-Agent",
                USER_AGENT,
                "yspappid",
                YSP_APP_ID,
                "sec-ch-ua",
                "\"Chromium\";v=\"148\", \"Not=A?Brand\";v=\"24\"",
                "sec-ch-ua-mobile",
                "?0",
                "sec-ch-ua-platform",
                "\"Windows\"");
    }

    static String header(Map<String, Object> headers, String name) {
        for (var entry : headers.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(name)) continue;
            Object value = entry.getValue();
            if (value instanceof List<?> values)
                return values.isEmpty() ? "" : String.valueOf(values.get(0));
            return value == null ? "" : value.toString();
        }
        return "";
    }

    private static JsonObject catalogOptions() {
        JsonObject headers = playerHeaders();
        headers.addProperty("Accept", "application/octet-stream");
        headers.addProperty("app-platform", "pc");
        headers.addProperty("app-version", "1.0.0");
        headers.addProperty("platform", "109");
        return json("headers", headers);
    }

    <T> T withTv(long deadline, Callable<T> task) throws Exception {
        if (!tvLock.tryLock(remaining(deadline, "電視端播放取址"), TimeUnit.MILLISECONDS))
            throw new ProxyException(504, "電視端播放取址: lock timed out");
        try {
            active();
            return task.call();
        } finally {
            tvLock.unlock();
        }
    }

    private void invalidateTv() {
        tv = null;
        tvPrefix = "tvplay:java:" + hex(random(8)) + ":";
    }

    void active() throws Exception {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Spider interrupted");
        if (destroyed.get()) throw new ProxyException(503, "播放服務已關閉");
    }

    static void cancellation(Exception error) throws Exception {
        if (Thread.currentThread().isInterrupted()
                || error instanceof InterruptedException
                || error instanceof CancellationException
                || error instanceof InterruptedIOException
                        && !(error instanceof java.net.SocketTimeoutException)
                        && "Thread interrupted".equals(error.getMessage())) throw error;
    }

    static void status(int code, String stage) throws ProxyException {
        if (code < 200 || code >= 300)
            throw new ProxyException(
                    code >= 400 && code <= 599 ? code : 502, stage + ": HTTP " + code);
    }

    static long deadline(long milliseconds) {
        return System.currentTimeMillis() + milliseconds;
    }

    static int remaining(long deadline, String stage) throws ProxyException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) throw new ProxyException(504, stage + ": 取址逾時");
        return (int) Math.min(15000, remaining);
    }

    static JsonObject json(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2)
            result.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
        return result;
    }

    static String str(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    static JsonObject object(JsonObject object, String name) throws ProxyException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject())
            throw new ProxyException(502, "invalid " + name + " object");
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonObject object, String name) throws ProxyException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray())
            throw new ProxyException(502, "invalid " + name + " array");
        return value.getAsJsonArray();
    }

    static boolean bool(JsonObject object, String name) {
        return object.has(name)
                && !object.get(name).isJsonNull()
                && object.get(name).getAsBoolean();
    }

    static String mapped(JsonObject map, String key, String fallback) {
        return map.has(key) ? map.get(key).getAsString() : fallback;
    }

    static String canonical(JsonObject object) {
        return object.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(value -> value.getKey() + "=" + value.getValue().getAsString())
                .collect(java.util.stream.Collectors.joining("&"));
    }

    static String signature(JsonObject params, String salt) {
        return Crypto.md5(canonical(params) + salt);
    }

    static String safe(Exception error) {
        String message =
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        message = message.split("\\R", 2)[0].replaceAll("https?://\\S+", "[網址]");
        return message.substring(0, Math.min(160, message.length()));
    }

    static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static String randomText(int length) {
        String alphabet = "ABCDEFGHIJKlMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < length; i++)
            result.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return result.toString();
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    static byte[] unhex(String value) {
        if ((value.length() & 1) != 0 || !value.matches("[0-9a-fA-F]*"))
            throw new IllegalArgumentException("Invalid hex");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++)
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    static String utf8(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    static String encode(String value) {
        return okhttp3.HttpUrl.parse("https://example.com/")
                .newBuilder()
                .addPathSegment(value)
                .build()
                .encodedPath()
                .substring(1);
    }

    static HttpUrl httpUrl(String value) {
        HttpUrl result = HttpUrl.parse(value);
        if (result == null
                || !result.username().isEmpty()
                || !result.password().isEmpty()
                || value.matches("(?s).*[\\x00-\\x20\\x7f\\\\].*"))
            throw new IllegalArgumentException("Invalid HTTP URL");
        return result;
    }

    static String query(String url, JsonObject params) {
        var builder = httpUrl(url).newBuilder();
        params.entrySet()
                .forEach(
                        value ->
                                builder.setQueryParameter(
                                        value.getKey(), value.getValue().getAsString()));
        return builder.build().toString();
    }

    static String mediaUrl(String location, JsonObject data) throws ProxyException {
        JsonElement value = data.get("extended_param");
        if (value == null || value.isJsonNull()) return location;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new ProxyException(502, "播放地址: invalid media parameters");
        String extension = value.getAsString();
        if (!extension.isEmpty() && !extension.startsWith("&"))
            throw new ProxyException(502, "播放地址: invalid media parameters");
        return location
                + (location.contains("?") || extension.isEmpty()
                        ? extension
                        : "?" + extension.substring(1));
    }

    static boolean playbackUrl(String url) {
        try {
            String host = httpUrl(url).host();
            return List.of(
                            "cctv.cn",
                            "cctv.com",
                            "cntv.cn",
                            "cntv.com",
                            "cctvpic.com",
                            "citvc.com",
                            "yangshipin.cn")
                    .stream()
                    .anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
        } catch (RuntimeException error) {
            return false;
        }
    }

    static String resolveUrl(String base, String value) throws ProxyException {
        HttpUrl url = httpUrl(base).resolve(value);
        if (url == null || !playbackUrl(url.toString()))
            throw new ProxyException(502, "直播 HLS: invalid resource URL");
        return url.toString();
    }

    static boolean channelId(String value) {
        return value != null && value.matches("[0-9]+");
    }

    private static String snapshotId(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return "";
        if (value.getAsJsonPrimitive().isString()) return value.getAsString();
        if (!value.getAsJsonPrimitive().isNumber()) return "";
        try {
            var number = value.getAsBigDecimal().toBigIntegerExact();
            return number.signum() >= 0
                            && number.compareTo(java.math.BigInteger.valueOf(9007199254740991L))
                                    <= 0
                    ? number.toString()
                    : "";
        } catch (ArithmeticException error) {
            return "";
        }
    }

    private static void validateChannel(Map<String, String> params) throws ProxyException {
        if (!channelId(params.get("pid"))
                || !channelId(params.get("cnlid"))
                || !List.of("fhd", "shd").contains(params.getOrDefault("defn", "fhd")))
            throw new ProxyException(400, "invalid playback channel id");
    }

    private static boolean refresh(Map<String, String> params) {
        return Arrays.asList(
                        params.getOrDefault("cache-control", "")
                                .toLowerCase(Locale.ROOT)
                                .replace(" ", "")
                                .split(","))
                .contains("no-cache");
    }

    private static String autoKey(Map<String, String> params) {
        return "auto:java:"
                + params.get("pid")
                + ":"
                + params.get("cnlid")
                + ":"
                + params.getOrDefault("defn", "fhd");
    }

    private static String entryKey(Map<String, String> params) {
        return autoKey(params) + ":entry:" + params.get("type") + ":" + params.get("generation");
    }

    private static Map<String, String> target(JsonObject entry) {
        return Map.of(
                "type",
                str(entry, "source"),
                "pid",
                str(entry, "pid"),
                "cnlid",
                str(entry, "cnlid"),
                "defn",
                str(entry, "defn"),
                "generation",
                str(entry, "generation"));
    }

    private static String epgTime(String value) {
        try {
            long seconds = Long.parseLong(value);
            SimpleDateFormat format = dateFormat("HH:mm:ss");
            String text = format.format(new Date(Math.multiplyExact(seconds, 1000)));
            Calendar calendar = format.getCalendar();
            return calendar.get(Calendar.ERA) == GregorianCalendar.AD
                            && calendar.get(Calendar.YEAR) <= 9999
                    ? text
                    : "";
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static SimpleDateFormat dateFormat(String pattern) {
        GregorianCalendar calendar =
                new GregorianCalendar(TimeZone.getTimeZone("GMT+08:00"), Locale.ROOT);
        calendar.setGregorianChange(new Date(Long.MIN_VALUE));
        calendar.setLenient(false);
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        format.setCalendar(calendar);
        return format;
    }

    private static String webCkey(String cnlid, String timestamp, String guid) throws Exception {
        String raw =
                "|"
                        + cnlid
                        + "|"
                        + timestamp
                        + "|mg3c3b04ba|"
                        + APP_VERSION
                        + "|"
                        + guid
                        + "|"
                        + PLATFORM
                        + "|https://www.yangshipin.c|mozilla/5.0 (windows nt"
                        + " ||Mozilla|Netscape|Win32|";
        int hash = raw.hashCode();
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(unhex("48e5918a74ae21c972b90cce8af6c8be"), "AES"),
                new IvParameterSpec(unhex("9a7e7d23610266b1d9fbf98581384d92")));
        return "--01"
                + hex(cipher.doFinal(("|" + hash + raw).getBytes(StandardCharsets.UTF_8)))
                        .toUpperCase(Locale.ROOT);
    }

    private static String ticket(String pid, String timestamp, String guid) {
        byte[]
                bytes =
                        (pid + "&" + timestamp + "&" + guid + "&" + YSP_APP_ID + "&bSCz8SqH8T")
                                .getBytes(StandardCharsets.UTF_8),
                key = unhex(TICKET_KEYSTREAM);
        if (bytes.length > key.length) throw new IllegalArgumentException("Invalid ticket payload");
        for (int i = 0; i < bytes.length; i++) bytes[i] ^= key[i];
        return hex(bytes);
    }

    private void closeReader(Net.StreamResult reader) {
        try {
            reader.close();
        } finally {
            readers.remove(reader);
        }
    }

    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) return;
        for (CompletableFuture<?> future : pending) future.cancel(true);
        Throwable failure = null;
        for (Net.StreamResult reader : readers) {
            try {
                reader.close();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        readers.clear();
        for (Net session : sessions) {
            try {
                session.close();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        sessions.clear();
        try {
            if (wasm != null) wasm.close();
        } catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Cannot close Yangshipin", failure);
    }
}
