package com.collabhub.platform.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统自检接口。
 *
 * <p>{@code GET /api/v1/system/info} —— 返回应用与数据库的实时状态。
 * 部署后第一件事就是调它，比看日志快。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    private final SystemInfoService systemInfoService;

    public SystemInfoController(SystemInfoService systemInfoService) {
        this.systemInfoService = systemInfoService;
    }

    @GetMapping("/info")
    public SystemInfo info() {
        return systemInfoService.load();
    }
}
