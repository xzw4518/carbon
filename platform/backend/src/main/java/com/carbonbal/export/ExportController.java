package com.carbonbal.export;

import com.carbonbal.auth.PageAccess;
import com.carbonbal.common.ApiResponse;
import com.carbonbal.common.BizException;
import com.carbonbal.identity.PageCatalog;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 统一台账导出入口：一次实现，避免在每个列表接口重复写 CSV。 */
@RestController
@RequestMapping("/api")
public class ExportController {
    private final ExportService service;

    public ExportController(ExportService service) {
        this.service = service;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String resource,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String keyword) {
        // 统一入口必须按 resource 校验页面权限，否则可绕过页面拿到其他模块数据
        String page = PageCatalog.pageForExportResource(resource);
        if (page != null && !PageAccess.allows(page)) throw BizException.pageForbidden();
        ExportService.CsvFile file = service.export(resource, from, to, keyword);
        String encoded = URLEncoder.encode(file.name(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file.bytes());
    }
}
