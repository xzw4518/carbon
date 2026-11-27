package com.carbonbal.file;

import com.carbonbal.auth.PageAccess;
import com.carbonbal.common.ApiResponse;
import com.carbonbal.common.BizException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/files")
public class FileController {

    /** 附件只服务于「原始凭证」与「第三方报告」两个页面。 */
    private static final String[] FILE_PAGES = {"evidence.list", "third.report"};

    private final FileService service;

    public FileController(FileService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse upload(@RequestParam("file") MultipartFile file,
                              @RequestParam(required = false) String bizType,
                              @RequestParam(required = false) String bizId) {
        assertPage();
        return ApiResponse.ok(service.store(file, bizType, bizId));
    }

    @GetMapping
    public ApiResponse list(@RequestParam(required = false) String bizType,
                            @RequestParam(required = false) String bizId) {
        assertPage();
        return ApiResponse.ok(service.list(bizType, bizId));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        assertPage();
        FileObject o = service.meta(id);
        byte[] bytes = service.bytes(id);
        String encoded = URLEncoder.encode(o.getName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-SHA256", o.getSha256())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.ok(null);
    }

    private static void assertPage() {
        if (!PageAccess.allowsAny(FILE_PAGES)) throw BizException.pageForbidden();
    }
}
