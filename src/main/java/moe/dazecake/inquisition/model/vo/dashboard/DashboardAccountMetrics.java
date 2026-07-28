package moe.dazecake.inquisition.model.vo.dashboard;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class DashboardAccountMetrics {
    private Long eligibleDaily = 0L;
    private Long missingLogin = 0L;
    private Long frozen = 0L;
    private Long expiringWithinSevenDays = 0L;
    private Long newAccountsToday = 0L;
    private Long validAccounts = 0L;
}
