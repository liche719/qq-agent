package com.liche.wechatagent.bilibili;

import org.springframework.data.jpa.repository.JpaRepository;

/** 单行表，只按固定 id 读写（见 {@link BiliCredential#SINGLETON_ID}） */
public interface BiliCredentialRepository extends JpaRepository<BiliCredential, Integer> {
}
