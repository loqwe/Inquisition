package moe.dazecake.inquisition.model.vo.dashboard;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class DashboardBusinessMetrics {
    private Double dayIncome = 0.0;
    private Double monthIncome = 0.0;
}
