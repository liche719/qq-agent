package com.liche.qqagent.bilibili;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * B 站扫码登录后存下来的凭据（2026-10-20 加，DDL 见 {@code deploy/postgres/V20__create_bili_credential.sql}）。
 *
 * <p><b>为什么要它</b>：原来只有服务器 `.env` 里的 {@code BILI_SESSDATA}，而且**只有一个 SESSDATA 字段**——
 * 读字幕够用，但只要涉及"写"（发评论、回复）就会被 B 站以 {@code -111 CSRF 校验失败} 拒掉，
 * 因为缺 {@code bili_jct}。扫码登录的响应头会**同时**下发这两者，所以做成"扫一次、存起来、以后一直用"。
 *
 * <p>只有一行（id = 1）。{@code biliJct} 允许为空：只读场景不需要它，别因此判定"没登录"。
 */
@Entity
@Table(name = "bili_credential")
@Getter
@Setter
@NoArgsConstructor
public class BiliCredential {

    /** 固定单行 */
    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id = SINGLETON_ID;

    @Column(name = "sessdata", length = 1024, nullable = false)
    private String sessdata = "";

    /** 写操作（评论/回复）的 csrf token；扫码登录才会有 */
    @Column(name = "bili_jct", length = 256, nullable = false)
    private String biliJct = "";

    @Column(name = "mid", length = 64, nullable = false)
    private String mid = "";

    @Column(name = "uname", length = 128, nullable = false)
    private String uname = "";

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public boolean readable() {
        return sessdata != null && !sessdata.isBlank();
    }

    /** 能不能做写操作（发评论/回复）——必须带 bili_jct */
    public boolean writable() {
        return readable() && biliJct != null && !biliJct.isBlank();
    }
}
