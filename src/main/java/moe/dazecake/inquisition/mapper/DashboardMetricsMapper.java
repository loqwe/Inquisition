package moe.dazecake.inquisition.mapper;

import moe.dazecake.inquisition.model.vo.dashboard.DashboardAccountMetrics;
import moe.dazecake.inquisition.model.vo.dashboard.DashboardBusinessMetrics;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface DashboardMetricsMapper {

    @Select({
            "SELECT",
            "COALESCE(SUM(CASE WHEN a.`delete` = 0 AND a.freeze = 0",
            "  AND a.task_type = 'daily' AND a.expire_time >= #{now} THEN 1 ELSE 0 END), 0) AS eligibleDaily,",
            "COALESCE(SUM(CASE WHEN a.`delete` = 0 AND a.freeze = 0",
            "  AND a.task_type = 'daily' AND a.expire_time >= #{now}",
            "  AND NOT EXISTS (SELECT 1 FROM log l",
            "    WHERE l.account_id = a.id AND l.time >= #{gameDayStart}",
            "    AND l.`delete` = 0 AND UPPER(l.level) = 'INFO'",
            "    AND l.`from` IS NOT NULL AND l.`from` <> '' AND UPPER(l.`from`) <> 'SYSTEM'",
            "    AND l.title LIKE '%登录成功%')",
            "  AND NOT EXISTS (SELECT 1 FROM task_assignment_history h",
            "    WHERE h.account_id = a.id AND h.finished_at >= #{gameDayStart}",
            "    AND h.status = 'COMPLETED' AND h.task_type = 'daily' AND h.task_mode = 'NORMAL')",
            "  THEN 1 ELSE 0 END), 0) AS missingLogin,",
            "COALESCE(SUM(CASE WHEN a.`delete` = 0 AND a.freeze = 1",
            "  AND a.expire_time >= #{now} THEN 1 ELSE 0 END), 0) AS frozen,",
            "COALESCE(SUM(CASE WHEN a.`delete` = 0 AND a.expire_time > #{now}",
            "  AND a.expire_time <= #{sevenDaysLater} THEN 1 ELSE 0 END), 0) AS expiringWithinSevenDays,",
            "COALESCE(SUM(CASE WHEN a.`delete` = 0 AND a.expire_time >= #{now}",
            "  AND a.create_time >= #{gameDayStart} AND a.create_time < #{gameDayEnd}",
            "  THEN 1 ELSE 0 END), 0) AS newAccountsToday,",
            "COALESCE(SUM(CASE WHEN a.`delete` = 0 AND a.expire_time >= #{now}",
            "  THEN 1 ELSE 0 END), 0) AS validAccounts",
            "FROM account a"
    })
    DashboardAccountMetrics selectAccountMetrics(@Param("now") LocalDateTime now,
                                                  @Param("gameDayStart") LocalDateTime gameDayStart,
                                                  @Param("gameDayEnd") LocalDateTime gameDayEnd,
                                                  @Param("sevenDaysLater") LocalDateTime sevenDaysLater);

    @Select({
            "SELECT",
            "COALESCE(SUM(CASE WHEN state = 1 AND update_time >= #{dayStart}",
            "  AND update_time < #{dayEnd} THEN COALESCE(actual_pay_amount, 0) ELSE 0 END), 0) AS dayIncome,",
            "COALESCE(SUM(CASE WHEN state = 1 AND update_time >= #{monthStart}",
            "  AND update_time < #{monthEnd} THEN COALESCE(actual_pay_amount, 0) ELSE 0 END), 0) AS monthIncome",
            "FROM bill"
    })
    DashboardBusinessMetrics selectBusinessMetrics(@Param("dayStart") LocalDateTime dayStart,
                                                    @Param("dayEnd") LocalDateTime dayEnd,
                                                    @Param("monthStart") LocalDateTime monthStart,
                                                    @Param("monthEnd") LocalDateTime monthEnd);
}
