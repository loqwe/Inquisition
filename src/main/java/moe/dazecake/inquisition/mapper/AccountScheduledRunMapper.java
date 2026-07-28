package moe.dazecake.inquisition.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AccountScheduledRunMapper extends BaseMapper<AccountScheduledRunEntity> {
    @Select({
            "SELECT id, account_id, scheduled_for, game_day, status, attempt_count,",
            "next_retry_at, last_error, created_at, updated_at, finished_at",
            "FROM account_scheduled_run",
            "WHERE status = 'WAITING'",
            "OR (status = 'RETRY_WAIT' AND next_retry_at <= #{now})",
            "ORDER BY scheduled_for, id",
            "LIMIT #{limit}"
    })
    List<AccountScheduledRunEntity> selectDispatchable(@Param("now") LocalDateTime now,
                                                       @Param("limit") int limit);

    @Select({
            "<script>",
            "SELECT run.id, run.account_id, run.scheduled_for, run.game_day, run.status,",
            "run.attempt_count, run.next_retry_at, run.last_error, run.created_at,",
            "run.updated_at, run.finished_at",
            "FROM account_scheduled_run run",
            "INNER JOIN (",
            "SELECT account_id, MAX(id) AS id",
            "FROM account_scheduled_run",
            "WHERE account_id IN",
            "<foreach collection='accountIds' item='accountId' open='(' separator=',' close=')'>",
            "#{accountId}",
            "</foreach>",
            "GROUP BY account_id",
            ") latest ON latest.id = run.id",
            "</script>"
    })
    List<AccountScheduledRunEntity> selectLatestByAccountIds(
            @Param("accountIds") Collection<Long> accountIds);
}
