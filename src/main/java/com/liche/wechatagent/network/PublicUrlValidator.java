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
            return (first & 0xfe) == 0xfc
                    || (first == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8);
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
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113);
    }
}
