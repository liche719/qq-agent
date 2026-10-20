package com.liche.wechatagent.bilibili;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * B 站凭据的**唯一来源**（2026-10-20 加）：库里有就用库里的，没有才回落到 `.env`。
 *
 * <p>顺序是刻意的：**扫码登录存进库里的更新、更全**（带 {@code bili_jct}），
 * 而 `.env` 那份是历史遗留（只有 SESSDATA）、留着当兜底，别让一次误清空把取字幕也弄挂。
 *
 * <p><b>凭据绝不进日志</b>：这里只打"有没有"和长度，不打内容（坑 5）。
 */
@Service
public class BiliCredentialService {

    private static final Logger log = LoggerFactory.getLogger(BiliCredentialService.class);

    private final BiliCredentialRepository repository;
    private final String envSessdata;

    public BiliCredentialService(BiliCredentialRepository repository,
                                 @Value("${bilibili.sessdata:}") String envSessdata) {
        this.repository = repository;
        this.envSessdata = envSessdata == null ? "" : envSessdata.trim();
    }

    /** 当前生效的凭据（库里优先，回落 .env）；两者都没有时返回空对象 */
    public BiliCredential current() {
        BiliCredential stored = stored();
        if (stored != null && stored.readable()) {
            return stored;
        }
        BiliCredential fallback = new BiliCredential();
        fallback.setSessdata(envSessdata);
        fallback.setUpdatedAt(LocalDateTime.now());
        return fallback;
    }

    /** 拼成请求头用的 Cookie 串；没有凭据时返回空串 */
    public String cookieHeader() {
        BiliCredential credential = current();
        if (!credential.readable()) {
            return "";
        }
        StringBuilder cookie = new StringBuilder(BiliClientCookieSupport.normalize(credential.getSessdata()));
        if (credential.getBiliJct() != null && !credential.getBiliJct().isBlank()) {
            cookie.append("; bili_jct=").append(credential.getBiliJct().trim());
        }
        if (credential.getMid() != null && !credential.getMid().isBlank()) {
            cookie.append("; DedeUserID=").append(credential.getMid().trim());
        }
        return cookie.toString();
    }

    /** 扫码成功后保存（整条 Cookie 里挑出我们需要的字段） */
    public void save(String rawCookie, String mid, String uname) {
        BiliCredential credential = storedOrNew();
        String sessdata = BiliClientCookieSupport.field(rawCookie, "SESSDATA");
        String biliJct = BiliClientCookieSupport.field(rawCookie, "bili_jct");
        if (sessdata.isBlank()) {
            throw new IllegalArgumentException("登录成功但没拿到 SESSDATA，这次不保存");
        }
        credential.setSessdata(sessdata);
        credential.setBiliJct(biliJct);
        if (mid != null && !mid.isBlank()) {
            credential.setMid(mid);
        }
        if (uname != null && !uname.isBlank()) {
            credential.setUname(uname);
        }
        credential.setUpdatedAt(LocalDateTime.now());
        repository.save(credential);
        log.info("B站凭据已更新：uname={} 可读={} 可写={}", credential.getUname(),
                credential.readable(), credential.writable());
    }

    /** 面板上的「退出登录」：只清库里的，`.env` 那份不动 */
    public void clear() {
        repository.findById(BiliCredential.SINGLETON_ID).ifPresent(repository::delete);
        log.info("B站凭据已清除（库里那份）");
    }

    /** 给面板/工具看的状态，不含任何凭据内容 */
    public java.util.Map<String, Object> status() {
        BiliCredential stored = stored();
        boolean fromDb = stored != null && stored.readable();
        BiliCredential effective = fromDb ? stored : current();
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("configured", effective.readable());
        map.put("readable", effective.readable());
        map.put("writable", effective.writable());
        map.put("source", fromDb ? "扫码登录（库里）" : (envSessdata.isBlank() ? "未配置" : "服务器 .env（只有 SESSDATA）"));
        map.put("uname", effective.getUname() == null ? "" : effective.getUname());
        map.put("mid", effective.getMid() == null ? "" : effective.getMid());
        map.put("updatedAt", effective.getUpdatedAt() == null ? "" : effective.getUpdatedAt().toString());
        return map;
    }

    private BiliCredential stored() {
        return repository.findById(BiliCredential.SINGLETON_ID).orElse(null);
    }

    private BiliCredential storedOrNew() {
        Optional<BiliCredential> existing = repository.findById(BiliCredential.SINGLETON_ID);
        if (existing.isPresent()) {
            return existing.get();
        }
        BiliCredential created = new BiliCredential();
        created.setId(BiliCredential.SINGLETON_ID);
        return created;
    }
}
