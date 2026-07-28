package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.annotation.Login;
import moe.dazecake.inquisition.model.vo.dashboard.AdminDashboardOverviewVO;
import moe.dazecake.inquisition.service.impl.AdminDashboardOverviewService;
import moe.dazecake.inquisition.utils.GameDayClock;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
public class DashboardController {

    @Resource
    AdminDashboardOverviewService dashboardOverviewService;

    @Login
    @GetMapping("/getDashboardOverview")
    public Result<AdminDashboardOverviewVO> getDashboardOverview() {
        return Result.success(dashboardOverviewService.getOverview(GameDayClock.now()), "查询成功");
    }
}
