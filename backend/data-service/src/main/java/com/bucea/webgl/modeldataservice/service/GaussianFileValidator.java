package com.bucea.webgl.modeldataservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.zip.Inflater;

/** Reads bounded chunks; never allocates a buffer the size of the scene. */
@Component
public class GaussianFileValidator {
    private final ObjectMapper json;
    public GaussianFileValidator(ObjectMapper json) { this.json = json; }
    public record Bounds(double[] min, double[] max) {}
    public record RadInfo(long leafCount, long totalCount, int chunks, int version, Bounds bounds) {}
    public record PlyInfo(long count, Bounds bounds) {}
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
    private static long align(long value) { return (value + 7) & ~7L; }
    private static byte[] read(RandomAccessFile file, long offset, int count) throws IOException {
        require(offset >= 0 && count >= 0 && offset <= file.length() - count, "高斯文件被截断或索引越界");
        byte[] bytes = new byte[count]; file.seek(offset); file.readFully(bytes); return bytes;
    }
    private static ByteBuffer le(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private JsonNode header(RandomAccessFile file, long offset, String magic) throws IOException {
        byte[] prefix = read(file, offset, 8);
        require(new String(prefix, 0, 4, StandardCharsets.US_ASCII).equals(magic), "无效的 " + magic + " 文件标识");
        int size = le(prefix).getInt(4);
        require(size > 0 && size <= 16 * 1024 * 1024, "高斯文件索引头过大或无效");
        return json.readTree(read(file, offset + 8, size));
    }
    private static Bounds emptyBounds() {
        return new Bounds(new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                new double[]{Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY});
    }
    private static void include(Bounds bounds, int axis, double value) {
        require(Double.isFinite(value), "高斯坐标包含无效数值");
        bounds.min[axis] = Math.min(bounds.min[axis], value);
        bounds.max[axis] = Math.max(bounds.max[axis], value);
    }
    public RadInfo rad(Path path, IntConsumer progress) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            JsonNode meta = header(file, 0, "RAD0");
            require(meta.path("version").asInt() == 1 && meta.path("lodTree").asBoolean(), "仅支持带 LOD 树的 RAD v1 成果");
            require(meta.path("maxSh").asInt(-1) == 0, "当前入库支持 SH0 RAD，请使用既定离线转换参数");
            require(meta.path("chunkSize").asInt() == 65536, "不支持的 RAD 分块大小");
            long count = meta.path("count").asLong();
            require(count > 0 && count <= 200_000_000, "RAD 高斯数量超出支持范围");
            long start = 8 + align(le(read(file, 0, 8)).getInt(4));
            long allBytes = meta.path("allChunkBytes").asLong(-1);
            require(allBytes == file.length() - start, "RAD 文件长度与索引不匹配");
            JsonNode chunks = meta.path("chunks");
            require(chunks.isArray() && chunks.size() == (count + 65535) / 65536, "RAD 分块数量不匹配");
            BitSet seenChildren = new BitSet((int) count);
            Bounds bounds = emptyBounds();
            long previousEnd = 0, total = 0, leaves = 0, edges = 0;
            for (int index = 0; index < chunks.size(); index++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("入库任务已中断");
                JsonNode ref = chunks.get(index);
                long offset = ref.path("offset").asLong(-1), bytes = ref.path("bytes").asLong(-1);
                require(!ref.hasNonNull("filename"), "请选择单文件 RAD 成果，不支持外置分块文件");
                require(offset == previousEnd && bytes > 16 && bytes <= 32 * 1024 * 1024 && bytes <= allBytes - offset, "RAD 分块索引无效");
                byte[] data = read(file, start + offset, (int) bytes);
                ByteBuffer buffer = le(data);
                require(new String(data, 0, 4, StandardCharsets.US_ASCII).equals("RADC"), "RAD 数据块标识损坏");
                int headerSize = buffer.getInt(4);
                require(headerSize > 0 && headerSize < bytes - 16, "RAD 数据块头无效");
                JsonNode chunk = json.readTree(Arrays.copyOfRange(data, 8, 8 + headerSize));
                int n = chunk.path("count").asInt();
                require(chunk.path("version").asInt() == 1 && chunk.path("lodTree").asBoolean()
                        && chunk.path("base").asLong(-1) == total && n == Math.min(65536, count - total), "RAD 数据块序号或数量无效");
                int payloadStart = (int) (16 + align(headerSize));
                require(payloadStart <= bytes && buffer.getLong(payloadStart - 8) == bytes - payloadStart
                        && chunk.path("payloadBytes").asLong(-1) == bytes - payloadStart, "RAD 属性区长度无效");
                Map<String, byte[]> decoded = new HashMap<>();
                Map<String, String> encodings = new HashMap<>();
                long next = 0;
                for (JsonNode prop : chunk.path("properties")) {
                    String name = prop.path("property").asText(), encoding = prop.path("encoding").asText();
                    int width = switch (name + "/" + encoding) {
                        case "center/f32_lebytes", "center/f32" -> 12;
                        case "alpha/f16", "child_count/u16" -> 2;
                        case "rgb/r8_delta", "rgb/r8", "scales/ln_0r8", "orientation/oct88r8" -> 3;
                        case "child_start/u32" -> 4;
                        default -> throw new IllegalArgumentException("不支持的 RAD 属性编码: " + name + "/" + encoding);
                    };
                    long length = prop.path("bytes").asLong(-1);
                    require(prop.path("compression").asText().equals("gz") && prop.path("offset").asLong(-1) == next
                            && length > 0 && length <= bytes - payloadStart - next && !decoded.containsKey(name), "RAD 属性索引损坏");
                    decoded.put(name, inflate(data, payloadStart + (int) next, (int) length, n * width));
                    encodings.put(name, encoding); next += align(length);
                }
                require(decoded.size() == 7 && next == bytes - payloadStart, "RAD 属性缺失或长度不一致");
                ByteBuffer counts = le(decoded.get("child_count")), starts = le(decoded.get("child_start"));
                byte[] centers = decoded.get("center");
                for (int i = 0; i < n; i++) {
                    int children = Short.toUnsignedInt(counts.getShort(i * 2));
                    if (children == 0) leaves++;
                    else {
                        long first = Integer.toUnsignedLong(starts.getInt(i * 4));
                        require(first > total + i && first + children <= count, "RAD 存在越界或循环 LOD 引用");
                        int existing = seenChildren.nextSetBit((int) first);
                        require(existing < 0 || existing >= first + children, "RAD 存在重复父节点");
                        seenChildren.set((int) first, (int) first + children); edges += children;
                    }
                    for (int d = 0; d < 3; d++) {
                        int bits = 0;
                        for (int b = 0; b < 4; b++) {
                            int at = encodings.get("center").equals("f32_lebytes") ? b * n * 3 + d * n + i : (i * 3 + d) * 4 + b;
                            bits |= (centers[at] & 255) << (b * 8);
                        }
                        float value = Float.intBitsToFloat(bits);
                        require(Float.isFinite(value), "RAD 包含无效坐标");
                        if (children == 0) include(bounds, d, value);
                    }
                }
                total += n; previousEnd += bytes; progress.accept(index + 1);
            }
            require(total == count && edges == count - 1 && leaves > 0, "RAD LOD 树不完整");
            if (meta.hasNonNull("comment")) {
                JsonNode description = json.readTree(meta.get("comment").asText());
                if (description.has("input_splat_count")) require(leaves == description.path("input_splat_count").asLong()
                        - description.path("empty_splat_count").asLong(), "RAD 叶子数量与源数据记录不匹配");
            }
            return new RadInfo(leaves, total, chunks.size(), 1, bounds);
        }
    }
    private static byte[] inflate(byte[] data, int offset, int length, int expected) throws IOException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data, offset, length);
            byte[] output = new byte[expected + 1]; int written = 0;
            while (!inflater.finished() && written < output.length) {
                int n = inflater.inflate(output, written, output.length - written);
                if (n == 0) break;
                written += n;
            }
            require(inflater.finished() && written == expected && inflater.getRemaining() == 0, "RAD 压缩属性损坏或解压长度异常");
            return output;
        } catch (java.util.zip.DataFormatException error) { throw new IllegalArgumentException("RAD 压缩数据损坏", error); }
        finally { inflater.end(); }
    }
    public PlyInfo ply(Path path) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            StringBuilder line = new StringBuilder(); boolean ended = false;
            while (header.size() < 65536) {
                int value = input.read(); if (value < 0) break;
                header.write(value);
                if (value == '\n') { if (line.toString().strip().equals("end_header")) { ended = true; break; } line.setLength(0); }
                else line.append((char) value);
            }
            require(ended, "天空 PLY 缺少有效文件头");
            String text = header.toString(StandardCharsets.US_ASCII);
            require(text.startsWith("ply\n") || text.startsWith("ply\r\n"), "无效的天空 PLY 文件");
            require(text.contains("format binary_little_endian 1.0"), "天空暂仅支持二进制小端高斯 PLY");
            long count = 0; List<String> properties = new ArrayList<>();
            for (String row : text.split("\\r?\\n")) {
                if (row.startsWith("element ")) {
                    require(row.startsWith("element vertex ") && count == 0, "天空 PLY 只能包含高斯顶点");
                    count = Long.parseLong(row.substring(15).strip());
                }
                if (row.startsWith("property ")) {
                    require(row.startsWith("property float "), "天空高斯属性必须为 float");
                    properties.add(row.substring(15).strip());
                }
            }
            require(count > 0 && count <= 2_000_000 && properties.size() <= 80, "天空高斯数量或属性数量超出限制");
            for (String prop : List.of("x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2", "opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"))
                require(properties.contains(prop), "天空 PLY 缺少高斯属性: " + prop);
            require(new HashSet<>(properties).size() == properties.size(), "天空 PLY 属性重复");
            int stride = properties.size() * 4;
            require(Files.size(path) == header.size() + count * stride, "天空 PLY 文件长度不匹配");
            Bounds bounds = emptyBounds(); byte[] block = new byte[stride * 4096];
            int[] xyz = {properties.indexOf("x"), properties.indexOf("y"), properties.indexOf("z")};
            for (long done = 0; done < count;) {
                int n = (int) Math.min(4096, count - done), length = n * stride;
                if (input.readNBytes(block, 0, length) != length) throw new IOException("天空文件被截断");
                ByteBuffer values = le(block);
                for (int i = 0; i < n; i++) {
                    for (int p = 0; p < properties.size(); p++) require(Float.isFinite(values.getFloat(i * stride + p * 4)), "天空属性包含无效数值");
                    for (int d = 0; d < 3; d++) include(bounds, d, values.getFloat(i * stride + xyz[d] * 4));
                }
                done += n;
            }
            return new PlyInfo(count, bounds);
        }
    }
}
