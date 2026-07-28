package moe.dazecake.inquisition.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AccountMapper extends BaseMapper<AccountEntity> {
    @Delete("DELETE FROM account WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);

    @Select("SELECT CAST(config AS CHAR) FROM account WHERE id = #{id}")
    String selectConfigJsonById(@Param("id") Long id);

    @Select({
            "<script>",
            "<bind name=\"likeKeyword\" value=\"'%' + keyword + '%'\" />",
            "SELECT * FROM account",
            "WHERE `delete` = 0",
            "AND (account LIKE #{likeKeyword} OR name LIKE #{likeKeyword}",
            "<if test=\"idKeyword != null\"> OR id = #{idKeyword}</if>",
            ")",
            "ORDER BY CASE WHEN (",
            "<if test=\"idKeyword != null\">id = #{idKeyword} OR </if>",
            "account = #{keyword} OR name = #{keyword}",
            ") THEN 0 ELSE 1 END, id ASC",
            "</script>"
    })
    Page<AccountEntity> searchActiveExactFirst(Page<AccountEntity> page,
                                               @Param("keyword") String keyword,
                                               @Param("idKeyword") Long idKeyword);

    @Select({
            "SELECT a.* FROM account a",
            "WHERE a.`delete` = 0",
            "AND a.freeze = 0",
            "AND a.task_type = 'daily'",
            "AND a.expire_time >= #{now}",
            "AND NOT EXISTS (",
            "  SELECT 1 FROM log l",
            "  WHERE l.account_id = a.id",
            "  AND l.time >= #{gameDayStart}",
            "  AND l.`delete` = 0",
            "  AND UPPER(l.level) = 'INFO'",
            "  AND l.`from` IS NOT NULL",
            "  AND l.`from` <> ''",
            "  AND UPPER(l.`from`) <> 'SYSTEM'",
            "  AND l.title LIKE '%登录成功%'",
            ")",
            "AND NOT EXISTS (",
            "  SELECT 1 FROM task_assignment_history h",
            "  WHERE h.account_id = a.id",
            "  AND h.finished_at >= #{gameDayStart}",
            "  AND h.status = 'COMPLETED'",
            "  AND h.task_type = 'daily'",
            "  AND h.task_mode = 'NORMAL'",
            ")",
            "ORDER BY a.id ASC"
    })
    Page<AccountEntity> selectMissingDailyLoginPage(Page<AccountEntity> page,
                                                    @Param("now") LocalDateTime now,
                                                    @Param("gameDayStart") LocalDateTime gameDayStart);
}
