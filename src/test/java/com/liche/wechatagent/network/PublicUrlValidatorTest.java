package com.liche.wechatagent.network;

import okhttp3.Dns;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicUrlValidatorTest {

    @Test
    void rejectsPrivateAndReservedTargets() throws Exception {
        PublicUrlValidator validator = validatorFor("127.0.0.1");

        assertThrows(IllegalArgumentException.class, () -> validator.validate("http://internal.example/file.pdf"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("file:///etc/passwd"));
    }

    @Test
    void rejectsMixedDnsAnswersToPreventBypass() throws Exception {
        Dns dns = hostname -> List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1"));
        PublicUrlValidator validator = new PublicUrlValidator(dns);

        assertThrows(IllegalArgumentException.class, () -> validator.validate("https://mixed.example/report.pdf"));
    }

    @Test
    void acceptsPublicHttpTargets() throws Exception {
        PublicUrlValidator validator = validatorFor("8.8.8.8");

        assertDoesNotThrow(() -> validator.validate("https://public.example/path?query=1"));
    }

    private PublicUrlValidator validatorFor(String address) throws UnknownHostException {
        Dns dns = hostname -> List.of(InetAddress.getByName(address));
        return new PublicUrlValidator(dns);
    }
}
