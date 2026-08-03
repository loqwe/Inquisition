package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.annotation.Login;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

class DashboardEndpointAvailabilityTest {

    @Test
    void dashboardOverviewRouteIsRegisteredAndLoginProtected() throws Exception {
        Class<?> controller;
        try {
            controller = Class.forName("moe.dazecake.inquisition.controller.DashboardController");
        } catch (ClassNotFoundException exception) {
            fail("DashboardController must expose the administrator dashboard overview route");
            return;
        }

        Method endpoint = controller.getDeclaredMethod("getDashboardOverview");
        var mapping = endpoint.getAnnotation(GetMapping.class);

        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/getDashboardOverview"}, mapping.value());
        assertNotNull(endpoint.getAnnotation(Login.class));
    }
}
