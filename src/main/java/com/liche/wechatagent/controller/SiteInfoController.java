package com.liche.wechatagent.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站点公开信息（**不需要口令**，也不包含任何敏感数据）。
 *
 * <p>目前只用于页面底部展示 ICP 备案号：备案号由环境变量 {@code SITE_ICP}（配置项 {@code site.icp}）提供，
 * 未配置时返回空串，前端什么也不显示。备案通过后只需在服务器 {@code .env} 里填一行再重启容器即可。
 */
@RestController
@RequestMapping("/api/site")
public class SiteInfoController {

    private final String icp;

    public SiteInfoController(@Value("${site.icp:}") String icp) {
        this.icp = icp == null ? "" : icp.trim();
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("icp", icp);
        return result;
    }
}
