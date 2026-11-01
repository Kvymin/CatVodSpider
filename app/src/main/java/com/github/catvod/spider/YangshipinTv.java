package com.github.catvod.spider;

import static com.github.catvod.spider.Yangshipin.*;
import static com.github.catvod.spider.YangshipinData.*;

import android.util.Base64;

import com.github.catvod.utils.Crypto;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

final class YangshipinTv {

    private final Yangshipin owner;
    private final JsonObject profile;
    private String uid = "";
    private String fingerprint = "";
    private String sessionKey = "";
    private String guid = "";
    private long createdAt;
    private long lastHeartbeat;

    YangshipinTv(Yangshipin owner) {
        this.owner = owner;
        String stored = owner.local.get("tv-device:v1");
        JsonElement parsed = stored == null ? null : JsonParser.parseString(stored);
        JsonObject identity =
                parsed != null && parsed.isJsonObject()
                        ? parsed.getAsJsonObject()
                        : new JsonObject();
        if (!identityText(identity, "android_id").matches("[0-9a-f]{16}")
                || !identityText(identity, "mac").matches("[0-9a-f]{2}(?::[0-9a-f]{2}){5}")) {
            byte[] mac = random(6);
            mac[0] = (byte) ((mac[0] | 2) & 254);
            String text = hex(mac).replaceAll("(..)(?!$)", "$1:");
            identity = json("android_id", hex(random(8)), "mac", text);
            owner.local.set("tv-device:v1", identity.toString());
        }
        profile =
                json(
                        "hardware",
                        "mt5895",
                        "board",
                        "mt5895",
                        "brand",
                        "Sony",
                        "device",
                        "sony_xr_85z9k",
                        "manufacturer",
                        "Sony",
                        "model",
                        "XR-85Z9K",
                        "product",
                        "sony_xr_85z9k",
                        "tags",
                        "release-keys",
                        "build_type",
                        "user",
                        "user",
                        "build",
                        "resolution",
                        "7680*4320",
                        "display",
                        "XR-85Z9K-user 13 SONYTV.2022.XR_85Z9K 2024 release-keys",
                        "version_id",
                        "SONYTV.2022.XR_85Z9K",
                        "host",
                        "sony-tv-build",
                        "fingerprint",
                        "Sony/sony_xr_85z9k/sony_xr_85z9k:13/SONYTV.2022.XR_85Z9K/2024:user/release-keys");
        profile.addProperty("android_id", str(identity, "android_id"));
        profile.addProperty("mac", str(identity, "mac"));
    }

    private static String identityText(JsonObject identity, String field) {
        JsonElement value = identity.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
    }

    private void initializeUid() throws Exception {
        if (!uid.isEmpty()) return;
        StringBuilder source = new StringBuilder("1698");
        for (String field :
                List.of(
                        "hardware",
                        "board",
                        "brand",
                        "device",
                        "manufacturer",
                        "model",
                        "product",
                        "tags",
                        "build_type",
                        "user",
                        "resolution",
                        "mac")) source.append(str(profile, field));
        String hash =
                String.format(
                        Locale.ROOT,
                        "%016x%016x",
                        (long) source.toString().hashCode(),
                        (long) str(profile, "model").hashCode());
        uid =
                hex(MessageDigest.getInstance("SHA-1")
                                .digest(
                                        (str(profile, "android_id") + "|" + hash)
                                                .getBytes(StandardCharsets.UTF_8)))
                        .toUpperCase(Locale.ROOT);
        owner.active();
    }

    private JsonObject common() {
        JsonObject value =
                json(
                        "cctv_id",
                        uid,
                        "device_id",
                        str(profile, "android_id"),
                        "idfa",
                        "",
                        "idfv",
                        "",
                        "user_id",
                        "",
                        "app_key",
                        "1178c84d-4818-44ff-b415-02106e87e144",
                        "imei",
                        "",
                        "android_id",
                        str(profile, "android_id"),
                        "mac",
                        str(profile, "mac"),
                        "system_type",
                        "Android",
                        "device_type",
                        "TV",
                        "app_language",
                        "CHINESE",
                        "app_version",
                        TV_VERSION,
                        "sdk_version",
                        "",
                        "os_version",
                        "13",
                        "app_channel",
                        "dangbei",
                        "data_time",
                        Long.toString(System.currentTimeMillis()),
                        "device_builder_type",
                        str(profile, "build_type"),
                        "device_params",
                        str(profile, "device"),
                        "device_model",
                        "XR85Z9K");
        for (String key :
                List.of(
                        "hardware",
                        "board",
                        "brand",
                        "display",
                        "version_id",
                        "host",
                        "product",
                        "tags",
                        "user",
                        "fingerprint",
                        "manufacturer",
                        "resolution")) value.addProperty("device_" + key, str(profile, key));
        return value;
    }

    private JsonObject request(String url, JsonObject data, long deadline, String stage)
            throws Exception {
        return request(url, data, deadline, stage, new JsonObject(), false);
    }

    private JsonObject request(
            String url,
            JsonObject data,
            long deadline,
            String stage,
            JsonObject extra,
            boolean form)
            throws Exception {
        owner.active();
        JsonObject headers =
                json(
                        "Accept",
                        "application/json",
                        "Accept-Language",
                        "zh-CN,zh;q=0.8",
                        "Referer",
                        "api.cctv.cn",
                        "User-Agent",
                        "cctv_app_tv",
                        "UID",
                        str(profile, "android_id"),
                        "appChannel",
                        "dangbei",
                        "X-Uid",
                        uid,
                        "X-Fingerprint",
                        fingerprint,
                        "X-Version",
                        TV_VERSION,
                        "X-Timestamp",
                        Long.toString(System.currentTimeMillis()),
                        "X-Nonce",
                        UUID.randomUUID().toString(),
                        "Content-Type",
                        form ? "application/x-www-form-urlencoded" : "application/json");
        extra.entrySet().forEach(value -> headers.add(value.getKey(), value.getValue()));
        Reply reply =
                owner.exchange(
                        owner.net,
                        url,
                        json(
                                "method",
                                "POST",
                                "data",
                                data,
                                "postType",
                                form ? "form" : "json",
                                "headers",
                                headers),
                        stage,
                        deadline,
                        4 * 1024 * 1024);
        JsonObject result;
        if (reply.code() == 400 && TV_DEVICE_CODES.has(url)) {
            try {
                result = JsonParser.parseString(utf8(reply.body())).getAsJsonObject();
            } catch (Exception error) {
                throw new ProxyException(400, stage + ": HTTP 400; invalid JSON", error);
            }
            String code = resultCode(result);
            if (TV_DEVICE_CODES.getAsJsonArray(url).asList().stream()
                    .anyMatch(value -> value.getAsString().equals(code))) {
                owner.active();
                remaining(deadline, stage);
                return result;
            }
        }
        status(reply.code(), stage);
        remaining(deadline, stage);
        return JsonParser.parseString(utf8(reply.body())).getAsJsonObject();
    }

    private static String resultCode(JsonObject value) {
        for (String field : List.of("result", "code", "ret"))
            if (value.has(field) && !value.get(field).isJsonNull()) return str(value, field);
        return "";
    }

    private static JsonElement data(JsonObject value, String stage) throws ProxyException {
        String code = resultCode(value);
        if (!code.isEmpty() && !code.equals("0"))
            throw new ProxyException(502, stage + ": code=" + code);
        if (!value.has("data") || value.get("data").isJsonNull())
            throw new ProxyException(502, stage + ": missing data");
        return value.get("data");
    }

    private void bootstrap(long deadline) throws Exception {
        initializeUid();
        long timestamp = System.currentTimeMillis();
        long day0 = Math.floorDiv(timestamp + 28800000, 86400000) * 86400000 - 28800000;
        fingerprint = Crypto.sha256(Crypto.sha256(TV_APP_ID + uid + timestamp + day0));
        JsonObject start =
                request(
                        TV_BASE + "api/app/start/v1/01",
                        json("key", "app_start_d1", "value", common()),
                        deadline,
                        "電視端初始化",
                        json("UID", "", "X-Timestamp", Long.toString(timestamp)),
                        false);
        JsonElement key = data(start, "電視端初始化");
        sessionKey =
                decryptGcm(
                        key.isJsonPrimitive()
                                ? key.getAsString()
                                : str(key.getAsJsonObject(), "key"),
                        fingerprint.substring(0, 32));
        JsonObject body = json("device_name", "央视频电视投屏助手", "device_id", rsaDeviceId(uid));
        JsonObject cloud = request(TV_CLOUD + "get", body, deadline, "電視設備查詢");
        if (List.of("601", "2").contains(resultCode(cloud))) {
            cloud = request(TV_CLOUD + "register", body, deadline, "電視設備註冊");
            if (List.of("0", "694", "2").contains(resultCode(cloud))
                    && (!cloud.has("data")
                            || !cloud.get("data").isJsonObject()
                            || str(cloud.getAsJsonObject("data"), "guid").isEmpty()))
                cloud = request(TV_CLOUD + "get", body, deadline, "電視設備查詢");
        }
        JsonElement registered = data(cloud, "電視設備註冊");
        guid = registered.isJsonObject() ? str(registered.getAsJsonObject(), "guid") : "";
        if (guid.isBlank()) throw new ProxyException(502, "電視設備註冊: missing guid");
        createdAt = System.currentTimeMillis();
        lastHeartbeat = 0;
    }

    void heartbeat(long deadline) throws Exception {
        if (System.currentTimeMillis() - lastHeartbeat < 30000) return;
        JsonObject value = common();
        value.addProperty("network_type", "WiFi");
        value.addProperty("guid", guid);
        value.addProperty("other", "");
        JsonObject result =
                request(
                        TV_REPORT,
                        json("key", "app_heartbeat", "value", value),
                        deadline,
                        "電視設備心跳");
        String code = resultCode(result);
        if (!code.isEmpty() && !code.equals("0")) {
            sessionKey = "";
            throw new ProxyException(502, "電視設備心跳: code=" + code);
        }
        lastHeartbeat = System.currentTimeMillis();
    }

    JsonObject resolve(String liveId, long deadline) throws Exception {
        try {
            if (sessionKey.isEmpty() || System.currentTimeMillis() - createdAt >= PLAY_SESSION_MS)
                bootstrap(deadline);
            heartbeat(deadline);
            JsonObject body =
                    json(
                            "screenParam",
                            "7680-4320-280",
                            "rate",
                            "",
                            "systemType",
                            "ios",
                            "model",
                            "XR-85Z9K",
                            "id",
                            liveId,
                            "userId",
                            "BAEBFF2B-C516-4F34-ABC0-A824A6461CBD",
                            "clientSign",
                            "cctvVideo",
                            "deviceId",
                            json("serial", "", "imei", "", "android_id", ""));
            JsonObject one =
                    data(request(TV_BASE + "api/live/v1/01", body, deadline, "電視端畫質列表"), "電視端畫質列表")
                            .getAsJsonObject();
            String field = one.has("videoList") ? "videoList" : "videos";
            JsonObject selected = null;
            for (JsonElement element : array(one, field)) {
                if (!element.isJsonObject() || str(element.getAsJsonObject(), "url").isEmpty())
                    continue;
                JsonObject video = element.getAsJsonObject();
                if (selected == null || str(video, "rate").equals("36p")) selected = video;
                if (str(video, "rate").equals("36p")) break;
            }
            if (selected == null) throw new ProxyException(502, "電視端畫質列表: no usable URL");
            String rawUrl = str(selected, "url");
            if (!rawUrl.matches("https?://.*")) rawUrl = decryptGcm(rawUrl, sessionKey);
            JsonElement two =
                    data(
                            request(
                                    TV_BASE + "api/live/v1/02",
                                    json("guid", encryptGcm("", sessionKey)),
                                    deadline,
                                    "電視端播放授權"),
                            "電視端播放授權");
            String encrypted =
                    two.isJsonPrimitive()
                            ? two.getAsString()
                            : str(
                                    two.getAsJsonObject(),
                                    two.getAsJsonObject().has("appSecret")
                                            ? "appSecret"
                                            : "app_secret");
            String secret = decryptGcm(encrypted, sessionKey);
            String random =
                    hex(random(4)) + "-0000-" + hex(random(2)) + "-0000-00000000" + hex(random(2));
            JsonObject headers =
                    json(
                            "APPID",
                            TV_APP_ID,
                            "APPSIGN",
                            Crypto.md5(TV_APP_ID + secret + random),
                            "APPRANDOMSTR",
                            random);
            String appcommon =
                    json("adid", "", "an", "央视频电视投屏助手", "ap", "cctv_app_tv", "av", TV_VERSION)
                            .toString();
            JsonObject result =
                    request(
                            TV_VDN,
                            json("appcommon", appcommon, "url", rawUrl),
                            deadline,
                            "電視端播放地址",
                            headers,
                            true);
            if (!str(result, "succeed").equals("1") || !playbackUrl(str(result, "url")))
                throw new ProxyException(502, "電視端播放地址: no usable HLS URL");
            headers.addProperty("UID", str(profile, "android_id"));
            headers.addProperty("Referer", "api.cctv.cn");
            headers.addProperty("User-Agent", "cctv_app_tv");
            return json(
                    "url", str(result, "url"), "headers", headers, "rate", str(selected, "rate"));
        } catch (Exception error) {
            sessionKey = "";
            throw error;
        }
    }

    static String encryptGcm(String value, String key) throws Exception {
        byte[] nonce = random(12);
        Cipher cipher = gcm(Cipher.ENCRYPT_MODE, key, nonce);
        return Base64.encodeToString(
                YangshipinApp.concat(nonce, cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))),
                Base64.NO_WRAP);
    }

    static String decryptGcm(String value, String key) throws Exception {
        byte[] bytes = Base64.decode(value, Base64.DEFAULT);
        if (bytes.length < 28) throw new ProxyException(502, "電視端授權: invalid encrypted response");
        return utf8(
                gcm(Cipher.DECRYPT_MODE, key, Arrays.copyOf(bytes, 12))
                        .doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)));
    }

    private static Cipher gcm(int mode, String key, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                mode,
                new SecretKeySpec(Arrays.copyOf(key.getBytes(StandardCharsets.UTF_8), 32), "AES"),
                new GCMParameterSpec(128, nonce));
        return cipher;
    }

    static String rsaDeviceId(String value) throws Exception {
        String pem = TV_PUBLIC_KEY.replaceAll("-----[^-]+-----|\\s", "");
        var key =
                KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                key,
                new OAEPParameterSpec(
                        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return Base64.encodeToString(
                cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
}
