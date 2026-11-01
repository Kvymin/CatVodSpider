package com.github.catvod.spider;

import com.fongmi.quickjs.WasmRuntime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class YangshipinKeygen implements AutoCloseable {

    private static final WasmRuntime.Type I32 = WasmRuntime.Type.I32;
    private final Map<Integer, String> heap = new HashMap<>();
    private final WasmRuntime.Module module;
    private WasmRuntime.Instance instance;
    private Map<String, String> state = Map.of();
    private int nextHandle = 132;

    YangshipinKeygen(WasmRuntime runtime, byte[] bytes, long deadline) throws Exception {
        module = runtime.compileTimed(remaining(deadline), bytes);
        try {
            validate();
            Map<String, WasmRuntime.Function> imports = Map.of(
                "__wbg_get_9c1840f7ecd81363", runtime.function(signature(2, 1), this::get),
                "__wbindgen_string_get", runtime.function(signature(2, 0), this::stringGet),
                "__wbindgen_object_drop_ref", runtime.function(signature(1, 0), this::drop));
            instance =
                    runtime.instantiateTimed(remaining(deadline), module, Map.of("wbg", imports));
            if (instance.memory("memory").pages() == 0)
                throw new IllegalArgumentException("missing keygen memory");
        } catch (Exception | Error error) {
            try {
                close();
            } catch (RuntimeException | Error closing) {
                if (error != closing) error.addSuppressed(closing);
            }
            throw error;
        }
    }

    private static WasmRuntime.Signature signature(int params, int results) {
        return new WasmRuntime.Signature(
                java.util.Collections.nCopies(params, I32),
                java.util.Collections.nCopies(results, I32));
    }

    private void validate() {
        Map<String, WasmRuntime.Signature> required = Map.of(
            "get_token_rnd", signature(1, 0), "get_signature", signature(1, 0), "__wbindgen_malloc", signature(2, 1),
            "__wbindgen_free", signature(3, 0), "__wbindgen_add_to_stack_pointer", signature(1, 1));
        for (Map.Entry<String, WasmRuntime.Signature> export : required.entrySet()) {
            if (!module.signature(export.getKey()).equals(export.getValue()))
                throw new IllegalArgumentException(
                        "incompatible keygen export: " + export.getKey());
        }
        if (module.exports().stream()
                .noneMatch(
                        value ->
                                value.name().equals("memory")
                                        && value.kind() == WasmRuntime.Kind.MEMORY))
            throw new IllegalArgumentException("missing keygen memory");
    }

    private Object[] get(WasmRuntime.Instance owner, Object[] arguments) throws Exception {
        String path = text(owner.memory().read((Integer) arguments[0], (Integer) arguments[1]));
        String value =
                path.startsWith("cctvh5openapi.state.")
                        ? state.getOrDefault(path.substring(20), "")
                        : path.equals("window.location.host")
                                ? "www.yangshipin.cn"
                                : path.equals("window.location.protocol") ? "https:" : "";
        if (nextHandle == Integer.MAX_VALUE)
            throw new IllegalStateException("keygen handle budget exhausted");
        int handle = nextHandle++;
        heap.put(handle, value);
        return new Object[] {handle};
    }

    private Object[] stringGet(WasmRuntime.Instance owner, Object[] arguments) throws Exception {
        byte[] bytes =
                heap.getOrDefault((Integer) arguments[1], "").getBytes(StandardCharsets.UTF_8);
        int pointer = (Integer) owner.call("__wbindgen_malloc", bytes.length, 1)[0];
        WasmRuntime.Memory memory = owner.memory();
        memory.write(pointer, bytes);
        memory.write(
                (Integer) arguments[0],
                ByteBuffer.allocate(8)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(pointer)
                        .putInt(bytes.length)
                        .array());
        return new Object[0];
    }

    private Object[] drop(WasmRuntime.Instance owner, Object[] arguments) {
        heap.remove((Integer) arguments[0]);
        return new Object[0];
    }

    synchronized String call(String name, Map<String, String> values, long deadline)
            throws Exception {
        if (instance == null) throw new IllegalStateException("keygen is closed");
        state = Map.copyOf(values);
        try {
            int output =
                    (Integer)
                            instance.callTimed(
                                            remaining(deadline),
                                            "__wbindgen_add_to_stack_pointer",
                                            -16)[0];
            instance.callTimed(remaining(deadline), name, output);
            ByteBuffer result =
                    ByteBuffer.wrap(instance.memory().read(output, 8))
                            .order(ByteOrder.LITTLE_ENDIAN);
            int pointer = result.getInt(), length = result.getInt();
            String text = text(instance.memory().read(pointer, length));
            if (pointer != 0)
                instance.callTimed(remaining(deadline), "__wbindgen_free", pointer, length, 1);
            instance.callTimed(remaining(deadline), "__wbindgen_add_to_stack_pointer", 16);
            return text;
        } catch (Exception | Error error) {
            try {
                close();
            } catch (RuntimeException | Error closing) {
                if (error != closing) error.addSuppressed(closing);
            }
            throw error;
        } finally {
            state = Map.of();
        }
    }

    private static String text(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static long remaining(long deadline) throws java.util.concurrent.TimeoutException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0)
            throw new java.util.concurrent.TimeoutException("keygen deadline exceeded");
        return remaining;
    }

    @Override
    public synchronized void close() {
        try {
            if (instance != null) instance.close();
        } finally {
            instance = null;
            module.close();
            heap.clear();
            state = Map.of();
        }
    }
}
