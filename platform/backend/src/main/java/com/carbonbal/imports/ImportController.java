package com.carbonbal.imports;

import com.carbonbal.auth.PageAccess;
import com.carbonbal.common.ApiResponse;
import com.carbonbal.common.BizException;
import com.carbonbal.identity.PageCatalog;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/import")
public class ImportController {
    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ApiResponse run(@RequestParam String resource, @RequestParam("file") MultipartFile file) {
        assertPage(resource);
        service.assertSupported(resource);
        return ApiResponse.ok(service.run(resource, file));
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam String resource) {
        assertPage(resource);
        service.assertSupported(resource);
        ImportService.Template t = service.template(resource);
        String encoded = URLEncoder.encode(t.name(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(t.bytes());
    }

    /** 导入按 resource 做页面校验：没有对应页面的人不能下模板，也不能导入。 */
    private static void assertPage(String resource) {
        String page = PageCatalog.pageForImportResource(resource);
        if (page != null && !PageAccess.allows(page)) throw BizException.pageForbidden();
    }
}
