package me.internalizable.knowisearch.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Web controller for admin dashboard pages.
 * Pages use JavaScript to fetch data from REST APIs.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    @GetMapping("/dashboard")
    public String dashboard() {
        return "admin/dashboard";
    }

    @GetMapping("/cache")
    public String cacheManagement() {
        return "admin/cache";
    }
}

