package com.liche.wechatagent.maimemo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 墨墨接入的可变配置（键值表）。
 *
 * <p>存在的意义：个人 access token 有效期很短（墨墨 App 里生成，页面显示只有一天），
 * 过期后如果只能改服务器 {@code .env} 并重启容器就太折腾了。放进数据库后，
 * 用户可以直接在面板「背单词」页粘贴新 Token，立刻生效、无需登录服务器。
 * 数据库里的值优先于环境变量；清空即回落到环境变量。
 */
@Entity
@Table(name = "maimemo_setting")
@Getter
@Setter
@NoArgsConstructor
public class MaimemoSetting {

    @Id
    @Column(name = "setting_key", length = 64)
    private String key;

    @Column(name = "setting_value", length = 2048)
    private String value;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
