package com.github.catvod.spider;

import static com.github.catvod.spider.Yangshipin.*;
import static com.github.catvod.spider.YangshipinData.*;

import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class YangshipinApp {

    static int checksum(byte[] bytes) {
        int value = 0;
        for (byte part : bytes) value = (value * 131 + (part & 255)) & 0x7fffffff;
        return value;
    }

    static byte[] teaBlock(byte[] block, byte[] key) {
        ByteBuffer data = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN),
                keys = ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN);
        int left = data.getInt(), right = data.getInt(), sum = 0;
        int a = keys.getInt(), b = keys.getInt(), c = keys.getInt(), d = keys.getInt();
        for (int round = 0; round < 16; round++) {
            sum += 0x9e3779b9;
            left += ((right << 4) + a) ^ (right + sum) ^ ((right >>> 5) + b);
            right += ((left << 4) + c) ^ (left + sum) ^ ((left >>> 5) + d);
        }
        return concat(be32(left), be32(right));
    }

    private static byte[] teaPacket(byte[] data, byte[] key) {
        int padding = (8 - ((data.length + 10) % 8)) % 8;
        byte[] plain =
                concat(
                        new byte[] {(byte) ((random(1)[0] & 248) | padding)},
                        random(padding + 2),
                        data,
                        new byte[7]);
        byte[] previousPlain = new byte[8],
                previousCipher = new byte[8],
                output = new byte[plain.length];
        for (int offset = 0; offset < plain.length; offset += 8) {
            byte[] mixed = xor(Arrays.copyOfRange(plain, offset, offset + 8), previousCipher);
            byte[] cipher = xor(teaBlock(mixed, key), previousPlain);
            System.arraycopy(cipher, 0, output, offset, 8);
            previousPlain = mixed;
            previousCipher = cipher;
        }
        return output;
    }

    static JsonObject ckey(String cnlid) {
        int timestamp = (int) (System.currentTimeMillis() / 1000);
        String guid = hex(random(16));
        byte[] guard =
                pack(
                        concat(
                                be32(timestamp),
                                pack(guid.substring(guid.length() - 5)),
                                pack(""),
                                pack(""),
                                pack("-1")));
        byte[] encrypted =
                concat(
                        teaPacket(guard, unhex("110dbec10c23e7d2e56a1cad6914ef1b")),
                        be32(checksum(guard)));
        String guardText = hex(xor(encrypted, unhex("b3c953a06913ad4d"))).toUpperCase(Locale.ROOT);
        byte[] body =
                concat(
                        unhex("0000004200000004000004d2"),
                        be32((int) APP_LIVE_PLATFORM),
                        be32(0),
                        be32(timestamp));
        for (String value :
                List.of("dcgh", "_zj1A5Gh6QYcxWjIUGos2w==", APP_LIVE_VERSION, cnlid, guid))
            body = concat(body, pack(value));
        body = concat(body, be32(1), be32(1));
        for (String value :
                List.of(
                    hex(random(4)).toUpperCase(Locale.ROOT), "nil", "57eab0c4-2c58-44c6-8ae9-dd2757525dc5", "nil", "v0.1.000",
                    "com.cctv.yangshipin.app.iphone", Long.toString(APP_LIVE_PLATFORM), "ex_json_bus", "ex_json_vs", guardText)) body = concat(body, pack(value));
        byte[] packet = pack(body);
        System.arraycopy(be32(checksum(packet)), 0, packet, 18, 4);
        encrypted =
                concat(
                        teaPacket(packet, unhex("59b2f7cf725ef43c34fdd7c123411ed3")),
                        be32(checksum(packet)));
        String ckey =
                Base64.encodeToString(
                                xor(encrypted, unhex("842eed08f066e6ea48b4caa991ed6ff3")),
                                Base64.NO_WRAP)
                        .replace('+', '_')
                        .replace('/', '-')
                        .replaceAll("=+$", "");
        return json(
            "cKey", "--01" + ckey, "guid", guid, "fntick", Integer.toUnsignedString(timestamp),
            "flowid", UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT) + "_" + APP_LIVE_PLATFORM);
    }

    static JsonObject resolve(
            Yangshipin owner, String pid, String cnlid, String definition, long deadline)
            throws Exception {
        JsonObject params = json(
            "atime", "120", "livepid", pid, "cnlid", cnlid, "appVer", APP_LIVE_VERSION, "app_version", "300090", "caplv", "1", "cmd", "2",
            "defn", definition, "device", "iPhone", "encryptVer", "4.2", "getpreviewinfo", "0", "hevclv", "0", "lang", "zh-Hans_CN", "livequeue", "0",
            "logintype", "1", "nettype", "1", "newnettype", "1", "newplatform", Long.toString(APP_LIVE_PLATFORM),
            "platform", Long.toString(APP_LIVE_PLATFORM), "sdtfrom", "v3021", "spacode", "23", "spaudio", "1", "spdemuxer", "6", "spdrm", "2",
            "spdynamicrange", "1", "spflv", "1", "spflvaudio", "1", "sphdrfps", "60", "sphttps", "1",
            "spvcode", Base64.encodeToString("H(30:1080,60:1080|30:1080,60:1080)" .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP), "spvideo", "4",
            "stream", "1", "system", "1", "sysver", "ios18.2.1", "uhd_flag", "0", "playbacktime", "0");
        ckey(cnlid).entrySet().forEach(value -> params.add(value.getKey(), value.getValue()));
        JsonObject result =
                owner.requestJson(
                        owner.net,
                        APP_LIVE_URL,
                        json("params", params, "headers", json("User-Agent", "qqlive", "Accept", "application/json")),
                        "App 直播取址",
                        deadline);
        if (!str(result, "iretcode").equals("0"))
            throw new ProxyException(502, "App 直播取址: iretcode=" + str(result, "iretcode"));
        List<String> candidates = new ArrayList<>();
        candidates.add(str(result, "playurl"));
        JsonElement backup =
                result.has("backurl_list")
                        ? result.get("backurl_list")
                        : result.has("backurlList")
                                ? result.get("backurlList")
                                : result.get("backurl");
        if (backup instanceof JsonArray list) {
            for (JsonElement value : list) {
                if (value.isJsonObject())
                    candidates.add(
                            str(
                                    value.getAsJsonObject(),
                                    value.getAsJsonObject().has("url") ? "url" : "playurl"));
                else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
                    candidates.add(value.getAsString());
            }
        } else if (backup != null
                && backup.isJsonPrimitive()
                && backup.getAsJsonPrimitive().isString())
            candidates.addAll(Arrays.asList(backup.getAsString().split("[;,]\\s*(?=https?://)")));
        String url =
                candidates.stream()
                        .filter(Yangshipin::playbackUrl)
                        .findFirst()
                        .orElseThrow(() -> new ProxyException(502, "App 直播取址: no usable HLS URL"));
        JsonObject headers = json("User-Agent", "qqlive", "Referer", "https://live.cctv.cn/");
        if (result.has("http_header")) {
            for (var header : object(result, "http_header").entrySet()) {
                if (!header.getValue().isJsonPrimitive()
                        || !header.getValue().getAsJsonPrimitive().isString())
                    throw new ProxyException(502, "App 直播取址: invalid playback headers");
                headers.add(header.getKey(), header.getValue());
            }
        }
        return json("url", mediaUrl(url, result), "headers", headers);
    }

    static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] bytes : arrays) output.write(bytes, 0, bytes.length);
        return output.toByteArray();
    }

    static byte[] be32(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    static byte[] pack(String value) {
        return pack(value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] pack(byte[] value) {
        if (value.length > 65535) throw new IllegalArgumentException("Signature field too long");
        return concat(ByteBuffer.allocate(2).putShort((short) value.length).array(), value);
    }

    static byte[] xor(byte[] left, byte[] right) {
        byte[] result = new byte[left.length];
        for (int i = 0; i < left.length; i++)
            result[i] = (byte) (left[i] ^ right[i % right.length]);
        return result;
    }

    private YangshipinApp() {

    }
}
