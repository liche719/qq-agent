package com.liche.wechatagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminAccessFilterTest {

    @Test
    void dashboardRequiresBothAllowedIpAndKey() throws Exception {
        ManagementAccessProperties properties = new ManagementAccessProperties();
        properties.setAdminApiKey("expected-key");
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        assertEquals(403, dashboardStatus(filter, "192.168.1.50", "expected-key"));
        assertEquals(401, dashboardStatus(filter, "127.0.0.1", null));
        assertEquals(200, dashboardStatus(filter, "127.0.0.1", "expected-key"));
        assertEquals(200, dashboardStatus(filter, "0:0:0:0:0:0:0:1", "expected-key"));
    }

    @Test
    void whitelistDoesNotAllowPasswordlessDashboardAccess() throws Exception {
        ManagementAccessProperties properties = new ManagementAccessProperties();
        properties.setAdminApiKey("expected-key");
        properties.setAllowedIps("192.168.1.50");
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        assertEquals(401, dashboardStatus(filter, "192.168.1.50", null));
        assertEquals(200, dashboardStatus(filter, "192.168.1.50", "expected-key"));
    }

    @Test
    void validKeyCannotBypassActiveLockout() throws Exception {
        ManagementAccessProperties properties = new ManagementAccessProperties();
        properties.setAdminApiKey("expected-key");
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertEquals(401, dashboardStatus(filter, "127.0.0.1", "wrong"));
        }
        assertEquals(429, dashboardStatus(filter, "127.0.0.1", "wrong"));
        assertEquals(429, dashboardStatus(filter, "127.0.0.1", "expected-key"));
    }

    private int dashboardStatus(AdminAccessFilter filter, String address, String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/overview");
        request.setRemoteAddr(address);
        if (key != null) request.addHeader(AdminAccessFilter.API_KEY_HEADER, key);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void rejectsNonLoopbackManagementRequestWithoutKey() throws Exception {
        ManagementAccessProperties properties = new ManagementAccessProperties();
        properties.setRequireKey(true);
        properties.setAdminApiKey("expected-key");
        AdminAccessFilter filter = new AdminAccessFilter(properties);

        MockHttpServletRequest request = managementRequest("192.168.1.50");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsMatchingManagementKey() throws Exception {
        ManagementAccessProperties properties = new ManagementAccessProperties();
        properties.setRequireKey(true);
        properties.setAdminApiKey("expected-key");
        AdminAccessFilter filter = new AdminAccessFilter(properties);

        MockHttpServletRequest request = managementRequest("192.168.1.50");
        request.addHeader(AdminAccessFilter.API_KEY_HEADER, "expected-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
    }

    @Test
    void allowsLoopbackInLocalModeWithoutKey() throws Exception {
        ManagementAccessProperties properties = new ManagementAccessProperties();
        properties.setRequireKey(false);
        properties.setAllowLoopbackWithoutKey(true);
        AdminAccessFilter filter = new AdminAccessFilter(properties);

        MockHttpServletRequest request = managementRequest("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
    }

    private MockHttpServletRequest managementRequest(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/clawbot/bots");
        request.setRequestURI("/api/clawbot/bots");
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
