package moe.dazecake.inquisition.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
