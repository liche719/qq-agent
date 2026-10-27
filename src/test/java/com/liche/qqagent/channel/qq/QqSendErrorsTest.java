package com.liche.qqagent.channel.qq;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发送失败分类（2026-10-27 从 QqChannel 抽出）。
 *
 * <p>判错的两个实际后果：该重发却当成"结果未知" → 白丢一条能补发的回复；
 * 不该重发却重发 → 用户收到两条一样的消息。所以这三个判据必须有测试。
 */
class QqSendErrorsTest {

    @Test
    void clientErrorIsDefiniteRejection() {
        Exception e = HttpClientErrorException.create(
                org.springframework.http.HttpStatus.BAD_REQUEST, "bad", null, null, null);
        assertTrue(QqSendErrors.definiteRejection(e));
        assertFalse(QqSendErrors.resultUnknown(e));
    }

    @Test
    void connectFailureNeverReachedServer() {
        assertTrue(QqSendErrors.neverReachedServer(new ConnectException("Connection refused")));
        assertTrue(QqSendErrors.neverReachedServer(new UnknownHostException("no such host")));
        assertTrue(QqSendErrors.neverReachedServer(
                new SocketTimeoutException("Connect timed out")));
        assertFalse(QqSendErrors.resultUnknown(new ConnectException("Connection refused")));
    }

    @Test
    void readTimeoutIsResultUnknown() {
        // 请求已经发出去、只是没等到响应 → 可能已送达，不能重发
        Exception e = new SocketTimeoutException("Read timed out");
        assertFalse(QqSendErrors.neverReachedServer(e));
        assertTrue(QqSendErrors.resultUnknown(e));
    }

    @Test
    void serverErrorIsResultUnknown() {
        Exception e = HttpServerErrorException.create(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "gw", null, null, null);
        assertFalse(QqSendErrors.definiteRejection(e));
        assertTrue(QqSendErrors.resultUnknown(e));
    }
}
