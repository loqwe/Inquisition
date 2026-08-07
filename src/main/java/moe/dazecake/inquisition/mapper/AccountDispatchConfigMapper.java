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
            "AND NOT EXISTS (",
            "SELECT 1 FROM account_scheduled_run run",
            "WHERE run.account_id = account_dispatch_config.account_id",
            "AND run.status IN ('WAITING', 'RUNNING', 'RETRY_WAIT')",
            ")",
            "ORDER BY next_scheduled_at, account_id",
            "LIMIT #{limit}"
    })
    List<AccountDispatchConfigEntity> selectDue(@Param("now") LocalDateTime now,
                                                @Param("limit") int limit);

    @Select({
            "SELECT config.account_id, config.dispatch_mode, config.schedule_time,",
            "config.next_scheduled_at, config.activation_pending,",
            "config.created_at, config.updated_at",
            "FROM account_dispatch_config config",
            "JOIN account account_row ON account_row.id = config.account_id",
            "WHERE config.dispatch_mode = 'SCHEDULED'",
            "AND config.activation_pending = 0",
            "AND config.next_scheduled_at IS NULL",
            "AND account_row.task_type = 'daily'",
            "AND account_row.`delete` = 0",
            "AND account_row.expire_time > #{now}",
            "ORDER BY config.account_id",
            "LIMIT #{limit}"
    })
    List<AccountDispatchConfigEntity> selectMissingNext(@Param("now") LocalDateTime now,
                                                        @Param("limit") int limit);

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

    @Update({
            "UPDATE account_dispatch_config",
            "SET next_scheduled_at = #{nextScheduledAt}",
            "WHERE account_id = #{accountId}",
            "AND dispatch_mode = 'SCHEDULED'",
            "AND activation_pending = 0",
            "AND next_scheduled_at IS NULL"
    })
    int scheduleNext(@Param("accountId") Long accountId,
                     @Param("nextScheduledAt") LocalDateTime nextScheduledAt);

    @Update({
            "UPDATE account_dispatch_config",
            "SET next_scheduled_at = NULL",
            "WHERE account_id = #{accountId}",
            "AND dispatch_mode = 'SCHEDULED'",
            "AND activation_pending = 0"
    })
    int clearNext(@Param("accountId") Long accountId);
}
