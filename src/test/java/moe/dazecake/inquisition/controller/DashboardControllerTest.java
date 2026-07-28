package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.annotation.Login;
import moe.dazecake.inquisition.model.vo.dashboard.AdminDashboardOverviewVO;
import moe.dazecake.inquisition.service.impl.AdminDashboardOverviewService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardControllerTest {

    @Test
    void overviewEndpointIsAdministratorAuthenticatedAndReturnsServiceSnapshot() throws Exception {
        var controller = new DashboardController();
        controller.dashboardOverviewService = mock(AdminDashboardOverviewService.class);
        when(controller.dashboardOverviewService.getOverview(any())).thenReturn(new AdminDashboardOverviewVO());

        var result = controller.getDashboardOverview();

        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertNotNull(DashboardController.class.getMethod("getDashboardOverview").getAnnotation(Login.class));
    }
}
