package com.github.catvod.spider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class YangshipinProto {

    private record Field(int id, Object value) {

    }

    private static final class Reader {

        final byte[] bytes;
        int offset;

        Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        long varint() {
            long value = 0;
            for (int shift = 0; shift < 70 && offset < bytes.length; shift += 7) {
                int part = bytes[offset++] & 255;
                if (shift == 63 && part > 1)
                    throw new IllegalArgumentException("Invalid protobuf varint");
                value |= (long) (part & 127) << shift;
                if (part < 128) return value;
            }
            throw new IllegalArgumentException("Invalid protobuf varint");
        }
    }

    private static List<Field> fields(byte[] bytes) {
        Reader reader = new Reader(bytes);
        List<Field> fields = new ArrayList<>();
        while (reader.offset < bytes.length) {
            long key = reader.varint();
            if (key < 8 || key > 0xffffffffL)
                throw new IllegalArgumentException("Invalid protobuf field");
            int id = (int) (key >>> 3), wire = (int) (key & 7);
            if (wire == 0) fields.add(new Field(id, reader.varint()));
            else {
                long length = wire == 1 ? 8 : wire == 5 ? 4 : wire == 2 ? reader.varint() : -1;
                if (length < 0 || length > bytes.length - reader.offset)
                    throw new IllegalArgumentException("Truncated protobuf field");
                fields.add(
                        new Field(
                                id,
                                Arrays.copyOfRange(
                                        bytes, reader.offset, reader.offset + (int) length)));
                reader.offset += (int) length;
            }
        }
        return fields;
    }

    private static Object value(List<Field> fields, int id) {
        Object result = null;
        for (Field field : fields) if (field.id == id) result = field.value;
        return result;
    }

    private static byte[] response(byte[] bytes) {
        List<Field> fields = fields(bytes);
        if (!Long.valueOf(200).equals(value(fields, 1))
                || !(value(fields, 2) instanceof byte[] body))
            throw new IllegalArgumentException("Invalid protobuf response");
        return body;
    }

    private static String text(Object value) throws Exception {
        return value instanceof byte[] bytes
                ? StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                : "";
    }

    static String navigation(byte[] bytes) throws Exception {
        for (Field field : fields(response(bytes))) {
            if (field.id != 2 || !(field.value instanceof byte[] body)) continue;
            List<Field> row = fields(body);
            String name = text(value(row, 1)), feed = text(value(row, 4));
            if (!feed.isEmpty()
                    && (name.equals("电视")
                            || name.equals("電視")
                            || Long.valueOf(2).equals(value(row, 10)))) return feed;
        }
        throw new IllegalArgumentException("Missing TV feed");
    }

    static JsonArray channels(byte[] bytes) throws Exception {
        JsonArray channels = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (Field field : fields(response(bytes))) {
            if (field.id != 2 || !(field.value instanceof byte[] body)) continue;
            List<Field> module = fields(body);
            if (!text(value(module, 2)).equals("tvChannel")) continue;
            for (Field item : module) {
                if (item.id != 15 || !(item.value instanceof byte[] data)) continue;
                List<Field> row = fields(data);
                String name = text(value(row, 2)),
                        pid = text(value(row, 4)),
                        cnlid = text(value(row, 6));
                if (name.isEmpty()
                        || !Yangshipin.channelId(pid)
                        || !Yangshipin.channelId(cnlid)
                        || !seen.add(pid)) continue;
                JsonObject channel =
                        Yangshipin.json(
                            "name", name, "pid", pid, "cnlid", cnlid, "logo", text(value(row, 5)), "type", text(value(row, 11)),
                            "vip", value(row, 7) instanceof Long flag && flag != 0);
                channels.add(channel);
            }
        }
        if (channels.size() < 50) throw new IllegalArgumentException("Incomplete channel list");
        return channels;
    }

    private YangshipinProto() {

    }
}
