package moe.dazecake.inquisition.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Mapper
public interface AccountDispatchConfigMapper extends BaseMapper<AccountDispatchConfigEntity> {
    @Insert({
            "INSERT INTO account_dispatch_config",
            "(account_id, dispatch_mode, schedule_time, next_scheduled_at, activation_pending)",
            "VALUES (#{config.accountId}, #{config.dispatchMode}, #{config.scheduleTime},",
            "#{config.nextScheduledAt}, #{config.activationPending})",
            "ON DUPLICATE KEY UPDATE",
            "dispatch_mode = #{config.dispatchMode},",
            "schedule_time = #{config.scheduleTime},",
            "next_scheduled_at = #{config.nextScheduledAt},",
            "activation_pending = #{config.activationPending}"
    })
    int upsert(@Param("config") AccountDispatchConfigEntity config);

    @Select({
            "SELECT account_id, dispatch_mode, schedule_time, next_scheduled_at,",
            "activation_pending, created_at, updated_at",
            "FROM account_dispatch_config",
            "WHERE account_id = #{accountId}",
            "FOR UPDATE"
    })
    AccountDispatchConfigEntity selectByIdForUpdate(@Param("accountId") Long accountId);

    @Select({
            "SELECT account_id, dispatch_mode, schedule_time, next_scheduled_at,",
            "activation_pending, created_at, updated_at",
            "FROM account_dispatch_config",
            "WHERE dispatch_mode = 'SCHEDULED'",
            "AND next_scheduled_at <= #{now}",
            "ORDER BY next_scheduled_at, account_id"
    })
    List<AccountDispatchConfigEntity> selectDue(@Param("now") LocalDateTime now);

    @Update({
            "UPDATE account_dispatch_config",
            "SET next_scheduled_at = NULL",
            "WHERE account_id = #{accountId}",
            "AND dispatch_mode = 'SCHEDULED'",
            "AND activation_pending = 0",
            "AND next_scheduled_at = #{expectedScheduledAt}"
    })
    int clearDue(@Param("accountId") Long accountId,
                 @Param("expectedScheduledAt") LocalDateTime expectedScheduledAt);

    @Update({
            "UPDATE account_dispatch_config",
            "SET next_scheduled_at = #{nextScheduledAt}",
            "WHERE account_id = #{accountId}",
            "AND dispatch_mode = 'SCHEDULED'",
            "AND activation_pending = 0",
            "AND next_scheduled_at = #{expectedScheduledAt}"
    })
    int advanceDue(@Param("accountId") Long accountId,
                   @Param("expectedScheduledAt") LocalDateTime expectedScheduledAt,
                   @Param("nextScheduledAt") LocalDateTime nextScheduledAt);

    @Update({
            "UPDATE account_dispatch_config",
            "SET schedule_time = #{scheduleTime},",
            "next_scheduled_at = #{nextScheduledAt},",
            "activation_pending = 0",
            "WHERE account_id = #{accountId}",
            "AND activation_pending = 1"
    })
    int completePendingActivation(@Param("accountId") Long accountId,
                                  @Param("scheduleTime") LocalTime scheduleTime,
                                  @Param("nextScheduledAt") LocalDateTime nextScheduledAt);
}
