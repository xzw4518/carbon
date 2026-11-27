package com.carbonbal.bootstrap;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 静态入口转发：Spring 只对根路径做 welcome page，子目录 index 需显式转发。 */
@Controller
public class WebEntryController {

    @GetMapping({"/app", "/app/"})
    public String app() {
        return "forward:/app/index.html";
    }

    @GetMapping("/console")
    public String console() {
        return "forward:/console.html";
    }
}
