package com.carbonbal.common;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等过滤器专用的请求体工具：摘要、流式落盘、multipart 指纹、可回放请求包装。
 *
 * <p>设计要点（P1-4）：
 * <ul>
 *   <li><b>不允许无上限 {@code readAllBytes()}</b>：小请求驻留内存，超过 {@code maxInlineBytes}
 *       后边读边写临时文件并同步 digest，内存占用恒定为阈值量级；</li>
 *   <li><b>超过 {@code maxBodyBytes} 直接 413</b>：避免"读进内存再判断"造成的 OOM；</li>
 *   <li><b>multipart 不再放行</b>：表单字段 + 每个文件部分的元数据与内容摘要共同构成指纹，
 *       文件内容通过 {@link Part#getInputStream()} 流式摘要，不落内存；</li>
 *   <li><b>可回放</b>：落盘请求用 {@link CachedFileRequest} 包装后仍可被控制器完整读取。</li>
 * </ul>
 *
 * <p>本类是无状态工具类，除 {@link Captured} 持有的临时文件外不引入任何共享可变状态。
 */
public final class IdempotencySupport {

    /** 临时文件前缀：测试与运维清理都依赖它。 */
    public static final String SPOOL_PREFIX = "idem-spool-";

    private static final int BUFFER = 8192;

    private IdempotencySupport() {
    }

    /* ==================== 摘要 ==================== */

    /**
     * 返回 UTF-8 字符串的 SHA-256 十六进制摘要（小写）。
     *
     * @param s 待摘要字符串，null 视为空串
     * @return 64 位十六进制字符串
     */
    public static String sha256Hex(String s) {
        return toHex(digest().digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 返回字节数组的 SHA-256 十六进制摘要（小写）。
     *
     * @param b 待摘要字节，null 视为空数组
     * @return 64 位十六进制字符串
     */
    public static String sha256Hex(byte[] b) {
        return toHex(digest().digest(b == null ? new byte[0] : b));
    }

    /** 流式摘要：按 {@value #BUFFER} 字节块读取，调用方负责关闭输入流。 */
    private static String sha256Hex(InputStream in) throws IOException {
        MessageDigest md = digest();
        byte[] buf = new byte[BUFFER];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        return toHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    /**
     * 规范化幂等键指纹：方法 + 路径 + 请求体摘要。路径不含查询串，避免参数顺序影响重放。
     *
     * @param method HTTP 方法
     * @param path   请求路径
     * @param bodyHash 请求体（或 multipart 指纹）的十六进制摘要
     * @return 请求指纹
     */
    public static String canonical(String method, String path, String bodyHash) {
        return sha256Hex(method + "\n" + path + "\n" + bodyHash);
    }

    /* ==================== 请求体捕获 ==================== */

    /**
     * 捕获结果：内存副本或临时文件，二者必居其一。
     *
     * <p>用 try-with-resources 保证临时文件一定被删除（即使业务抛异常）。
     */
    public static final class Captured implements AutoCloseable {
        private final byte[] inline;
        private final Path temp;
        private final String hash;
        private final long size;

        private Captured(byte[] inline, Path temp, String hash, long size) {
            this.inline = inline;
            this.temp = temp;
            this.hash = hash;
            this.size = size;
        }

        /** 请求体摘要（十六进制 SHA-256）。 */
        public String hash() {
            return hash;
        }

        /** 请求体字节数。 */
        public long size() {
            return size;
        }

        /** 是否完全驻留内存（小请求）。 */
        public boolean inMemory() {
            return inline != null;
        }

        /** 临时文件路径；内存模式返回 null。 */
        public Path tempFile() {
            return temp;
        }

        /**
         * 用捕获结果包装原请求，保证下游控制器仍能读到完整请求体。
         *
         * @param original 原始请求
         * @return 可回放请求
         */
        public HttpServletRequest wrap(HttpServletRequest original) {
            return inline != null
                    ? new CachedBodyRequest(original, inline)
                    : new CachedFileRequest(original, temp, size);
        }

        @Override
        public void close() {
            if (temp == null) return;
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 临时文件删不掉不影响业务；JVM 退出时操作系统会回收
            }
        }
    }

    /**
     * 读一次请求体：小请求驻留内存，超阈值后转临时文件，超上限抛 {@link PayloadTooLargeException}。
     *
     * <p>读取过程只发生一次，因此 digest 与回放数据来自同一份字节，不存在"摘要与业务读到的内容不一致"。
     *
     * @param req       原始请求
     * @param maxInline 内存驻留上限（字节）
     * @param maxBody   请求体总上限（字节），超过即拒绝
     * @return 捕获结果（调用方负责 {@link Captured#close()}）
     * @throws PayloadTooLargeException 请求体超过 {@code maxBody}
     */
    public static Captured capture(HttpServletRequest req, int maxInline, int maxBody) throws IOException {
        int safeInline = Math.max(1, maxInline);
        long safeBody = Math.max(safeInline, maxBody);
        ByteArrayOutputStream mem = new ByteArrayOutputStream(Math.min(safeInline, 64 * 1024));
        Path temp = null;
        OutputStream out = null;
        InputStream in = req.getInputStream();
        byte[] buf = new byte[BUFFER];
        long total = 0;
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > safeBody) {
                    throw new PayloadTooLargeException("请求体超过上限 " + safeBody + " 字节");
                }
                if (out != null) {
                    out.write(buf, 0, n);
                } else if (total <= safeInline) {
                    mem.write(buf, 0, n);
                } else {
                    // 越过阈值：把已缓冲的部分落到临时文件，后续直接追加，内存不再增长
                    temp = Files.createTempFile(SPOOL_PREFIX, ".bin");
                    out = new BufferedOutputStream(Files.newOutputStream(temp));
                    out.write(mem.toByteArray());
                    mem.reset();
                    out.write(buf, 0, n);
                }
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(out);
            deleteQuietly(temp);
            throw e;
        }
        closeQuietly(out);

        if (temp != null) {
            String hash;
            try (InputStream fin = Files.newInputStream(temp)) {
                hash = sha256Hex(fin);
            }
            return new Captured(null, temp, hash, total);
        }
        byte[] bytes = mem.toByteArray();
        return new Captured(bytes, null, sha256Hex(bytes), total);
    }

    /** 关闭流，忽略异常（清理路径上不掩盖原始异常）。 */
    private static void closeQuietly(OutputStream out) {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // 关闭失败无副作用
        }
    }

    /** 删除临时文件，忽略异常（JVM 退出时操作系统兜底回收）。 */
    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 删除失败无副作用
        }
    }

    /* ==================== multipart 指纹 ==================== */
    /**
     * 计算 multipart 请求指纹：方法 + 路径 + 排序后的表单字段 + 每个文件部分（名/文件名/类型/大小/内容摘要）。
     *
     * <p>字段与文件描述都做了排序，保证相同内容不同顺序的提交得到相同指纹（重放判定稳定）。
     * 不做请求包装：容器已缓存解析结果，Spring 的 {@code StandardServletMultipartResolver}
     * 会复用同一份 parts，控制器照常读到文件。
     *
     * @param req multipart 请求
     * @return 请求指纹
     * @throws IOException 读取 part 内容失败
     */
    public static String multipartKey(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(req.getMethod()).append('\n').append(req.getRequestURI()).append('\n');

        TreeMap<String, List<String>> fields = new TreeMap<>();
        Map<String, String[]> params = req.getParameterMap();
        if (params != null) {
            params.forEach((k, v) -> fields.put(k, v == null ? List.of() : Arrays.asList(v)));
        }
        fields.forEach((k, vs) -> sb.append("field:").append(k).append('=')
                .append(String.join("\u0001", vs)).append('\n'));

        List<String> files = new ArrayList<>();
        Collection<Part> parts = parts(req);
        if (!parts.isEmpty()) {
            for (Part p : parts) {
                if (p.getSubmittedFileName() == null) continue;
                files.add(fileDescriptor(p.getName(), p.getSubmittedFileName(), p.getContentType(),
                        p.getSize(), p.getInputStream()));
            }
        } else {
            // 安全过滤器/容器包装后，请求外层可能不是 MultipartHttpServletRequest，需回溯原始请求
            MultipartHttpServletRequest multipart =
                    org.springframework.web.util.WebUtils.getNativeRequest(req, MultipartHttpServletRequest.class);
            if (multipart == null) {
                throw new IOException("请求声明为 multipart，但无法解析表单内容");
            }
            multipart.getMultiFileMap().forEach((name, list) -> {
                if (list == null) return;
                for (MultipartFile f : list) {
                    try {
                        files.add(fileDescriptor(name, f.getOriginalFilename(), f.getContentType(),
                                f.getSize(), f.getInputStream()));
                    } catch (IOException e) {
                        throw new UncheckedMultipartException(e);
                    }
                }
            });
        }
        Collections.sort(files);
        files.forEach(d -> sb.append(d).append('\n'));
        return sha256Hex(sb.toString());
    }

    /** 取 parts，容器未启用 multipart 配置时返回空集合（由调用方退化为文件元数据摘要）。 */
    private static Collection<Part> parts(HttpServletRequest req) {
        try {
            Collection<Part> parts = req.getParts();
            return parts == null ? List.of() : parts;
        } catch (IOException | ServletException | RuntimeException e) {
            return List.of();
        }
    }

    private static String fileDescriptor(String name, String fileName, String contentType, long size, InputStream in)
            throws IOException {
        String contentHash;
        try (InputStream stream = in) {
            contentHash = sha256Hex(stream);
        }
        return "file:" + name + ':' + (fileName == null ? "" : fileName) + ':'
                + (contentType == null ? "" : contentType) + ':' + size + ':' + contentHash;
    }

    /** 包装 {@link #multipartKey} 在 lambda 内抛出的 IOException。 */
    private static final class UncheckedMultipartException extends RuntimeException {
        UncheckedMultipartException(IOException cause) {
            super(cause);
        }
    }

    /* ==================== 异常 ==================== */

    /** 请求体超过上限：由过滤器翻译成 413，不进入业务。 */
    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }

    /* ==================== 请求包装 ==================== */

    /** 内存回放：小请求。 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步读取场景不会注册监听器
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset cs = getCharacterEncoding() == null ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }

    /** 临时文件回放：大请求。用完由 {@link Captured#close()} 删除。 */
    private static final class CachedFileRequest extends HttpServletRequestWrapper {
        private final Path file;

        CachedFileRequest(HttpServletRequest request, Path file, long size) {
            super(request);
            this.file = file;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            InputStream in = Files.newInputStream(file);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    try {
                        return in.available() == 0;
                    } catch (IOException e) {
                        return true;
                    }
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步读取场景不会注册监听器
                }

                @Override
                public int read() throws IOException {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return in.read(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    in.close();
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            Charset cs = getCharacterEncoding() == null ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }
}
