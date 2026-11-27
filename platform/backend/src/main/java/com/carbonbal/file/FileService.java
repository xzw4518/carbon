package com.carbonbal.file;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.audit.AuditService;
import com.carbonbal.auth.CurrentUser;
import com.carbonbal.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class FileService {
    private static final DateTimeFormatter DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    private final FileObjectMapper mapper;
    private final AuditService audit;

    @Value("${app.file.root:./data/files}")
    private String root;

    public FileService(FileObjectMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional
    public FileObject store(MultipartFile f, String bizType, String bizId) {
        if (f == null || f.isEmpty()) throw BizException.validation("文件为空");
        try {
            byte[] bytes = f.getBytes();
            String original = sanitizeName(f.getOriginalFilename());
            String ext = original.contains(".")
                    ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
            String key = LocalDate.now().format(DIR) + "/" + UUID.randomUUID()
                    + (ext.isEmpty() ? "" : "." + ext);
            Path target = Paths.get(root, key).toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);

            FileObject o = new FileObject();
            o.setOrgId(CurrentUser.orgId());
            o.setName(original);
            o.setExt(ext);
            o.setSize((long) bytes.length);
            o.setSha256(sha256(bytes));
            o.setStorageKey(key);
            o.setBizType(bizType);
            o.setBizId(bizId);
            o.setUploadedBy(CurrentUser.username());
            o.setDisabled(0);
            o.setCreatedAt(LocalDateTime.now());
            mapper.insert(o);
            audit.record("file_object", o.getId(), "upload", null,
                    java.util.Map.of("name", original, "size", o.getSize(), "sha256", o.getSha256()));
            return o;
        } catch (IOException e) {
            throw BizException.internal("文件写入失败", e.getMessage());
        }
    }

    public FileObject meta(Long id) {
        FileObject o = mapper.selectById(id);
        if (o == null || Integer.valueOf(1).equals(o.getDisabled())) {
            throw BizException.notFound("文件不存在");
        }
        if (!o.getOrgId().equals(CurrentUser.orgId())) {
            throw BizException.forbidden("无权访问其他企业的文件");
        }
        return o;
    }

    public byte[] bytes(Long id) {
        FileObject o = meta(id);
        try {
            Path p = Paths.get(root, o.getStorageKey()).toAbsolutePath().normalize();
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw BizException.notFound("文件内容缺失，可能已被清理", o.getStorageKey());
        }
    }

    public List<FileObject> list(String bizType, String bizId) {
        return mapper.selectList(Wrappers.<FileObject>lambdaQuery()
                .eq(FileObject::getOrgId, CurrentUser.orgId())
                .eq(FileObject::getDisabled, 0)
                .eq(bizType != null && !bizType.isBlank(), FileObject::getBizType, bizType)
                .eq(bizId != null && !bizId.isBlank(), FileObject::getBizId, bizId)
                .orderByDesc(FileObject::getId));
    }

    /** 批量按业务归属取文件，避免列表逐行查询。 */
    public List<FileObject> byBizIds(String bizType, java.util.Collection<String> bizIds) {
        if (bizIds == null || bizIds.isEmpty()) return List.of();
        return mapper.selectList(Wrappers.<FileObject>lambdaQuery()
                .eq(FileObject::getOrgId, CurrentUser.orgId())
                .eq(FileObject::getDisabled, 0)
                .eq(FileObject::getBizType, bizType)
                .in(FileObject::getBizId, bizIds)
                .orderByAsc(FileObject::getId));
    }

    /** 批量按主键取文件（第三方报告列表用）。 */
    public List<FileObject> byIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return mapper.selectList(Wrappers.<FileObject>lambdaQuery()
                .eq(FileObject::getOrgId, CurrentUser.orgId())
                .eq(FileObject::getDisabled, 0)
                .in(FileObject::getId, ids));
    }

    /** 把已上传文件绑定到业务对象；逐个校验租户，避免越权挂接。 */
    @Transactional
    public void bind(List<Long> fileIds, String bizType, String bizId) {
        if (fileIds == null || fileIds.isEmpty()) return;
        for (Long id : fileIds.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
            meta(id);
            mapper.update(null, Wrappers.<FileObject>lambdaUpdate()
                    .eq(FileObject::getId, id)
                    .set(FileObject::getBizType, bizType)
                    .set(FileObject::getBizId, bizId));
        }
    }

    /** 解绑业务对象上不在保留清单里的文件（文件本身保留，可再次挂接）。 */
    @Transactional
    public void unbindExcept(String bizType, String bizId, java.util.Collection<Long> keepIds) {
        List<FileObject> bound = byBizIds(bizType, List.of(bizId));
        for (FileObject f : bound) {
            if (keepIds != null && keepIds.contains(f.getId())) continue;
            mapper.update(null, Wrappers.<FileObject>lambdaUpdate()
                    .eq(FileObject::getId, f.getId())
                    .set(FileObject::getBizType, null)
                    .set(FileObject::getBizId, null));
        }
    }

    @Transactional
    public void remove(Long id) {
        FileObject o = meta(id);
        mapper.update(null, Wrappers.<FileObject>lambdaUpdate()
                .eq(FileObject::getId, id).set(FileObject::getDisabled, 1));
        audit.record("file_object", id, "delete", o, null);
    }

    /** 文件名清洗：只保留 basename，去掉路径分隔符与控制字符，限长 200（与列宽一致）。 */
    private static String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) return "file";
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isBlank()) return "file";
        if (name.length() > 200) {
            int dot = name.lastIndexOf('.');
            String ext = (dot > 0 && name.length() - dot <= 12) ? name.substring(dot) : "";
            name = name.substring(0, Math.max(1, 200 - ext.length())) + ext;
        }
        return name;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
