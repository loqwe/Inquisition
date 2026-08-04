package moe.dazecake.inquisition.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchTimeEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface AccountDispatchTimeMapper extends BaseMapper<AccountDispatchTimeEntity> {

    @Select({
            "SELECT schedule_time",
            "FROM account_dispatch_time",
            "WHERE account_id = #{accountId}",
            "ORDER BY schedule_time"
    })
    List<LocalTime> selectTimes(@Param("accountId") Long accountId);

    @Select({
            "<script>",
            "SELECT id, account_id, schedule_time, created_at, updated_at",
            "FROM account_dispatch_time",
            "WHERE account_id IN",
            "<foreach collection='accountIds' item='accountId' open='(' separator=',' close=')'>",
            "#{accountId}",
            "</foreach>",
            "ORDER BY account_id, schedule_time",
            "</script>"
    })
    List<AccountDispatchTimeEntity> selectByAccountIds(
            @Param("accountIds") Collection<Long> accountIds);

    @Delete("DELETE FROM account_dispatch_time WHERE account_id = #{accountId}")
    int deleteByAccountId(@Param("accountId") Long accountId);
}
