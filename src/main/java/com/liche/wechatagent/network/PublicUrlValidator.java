package com.liche.wechatagent.network;

import okhttp3.Dns;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/** Validates that agent-initiated downloads target publicly routable HTTP(S) hosts. */
@Component
public class PublicUrlValidator {

    private final Dns dns;

    public PublicUrlValidator() {
        this(Dns.SYSTEM);
    }

    PublicUrlValidator(Dns dns) {
        this.dns = dns;
    }

    public URI validate(String value) {
        URI uri = URI.create(value == null ? "" : value.trim());
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("unsupported scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("invalid host");
        }
        try {
            lookupPublic(uri.getHost());
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("unreachable host", exception);
        }
        return uri;
    }

    /** Used by OkHttp so each connection is checked again at DNS resolution time. */
    public List<InetAddress> lookupPublic(String hostname) throws UnknownHostException {
        List<InetAddress> addresses = dns.lookup(hostname);
        if (addresses.isEmpty() || addresses.stream().anyMatch(this::isNonPublicAddress)) {
            throw new UnknownHostException("private or unresolved address");
        }
        return addresses;
    }

    private boolean isNonPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            // **只放行全局单播 2000::/3**，其余整段拒绝。这一刀同时挡住几类"能绕回内网"的地址：
            // fc00::/7（ULA）、fe80::/10（链路本地）、::ffff:x.x.x.x（IPv4 映射，first 不是 0x20）、
            // 64:ff9b::/96（NAT64，可写 [64:ff9b::7f00:1] 打到 127.0.0.1）、2002::/16（6to4，内嵌 IPv4）。
            if ((first & 0xe0) != 0x20) {
                return true;
            }
            int second = Byte.toUnsignedInt(bytes[1]);
            if (second == 0x02) {
                return true;
            }
            return first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8;
        }
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 192 && second == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 192 && second == 88 && third == 99)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                // 240.0.0.0/4 保留段（含 255.255.255.255 广播）：224~239 已被 isMulticastAddress 覆盖
                || first >= 240;
    }
}
