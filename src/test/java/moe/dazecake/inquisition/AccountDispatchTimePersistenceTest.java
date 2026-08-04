package moe.dazecake.inquisition;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.dazecake.inquisition.mapper.AccountDispatchTimeMapper;
import moe.dazecake.inquisition.model.entity.AccountDispatchTimeEntity;
import moe.dazecake.inquisition.model.vo.account.AccountWithSanVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountDispatchTimePersistenceTest {

    @Test
    void mapperReadsTimesInAscendingOrderAndDeletesByAccount() throws Exception {
        var select = AccountDispatchTimeMapper.class
                .getMethod("selectTimes", Long.class)
                .getAnnotation(Select.class);
        var delete = AccountDispatchTimeMapper.class
                .getMethod("deleteByAccountId", Long.class)
                .getAnnotation(Delete.class);

        assertNotNull(select);
        assertNotNull(delete);
        var selectSql = normalizedSql(select.value());
        assertTrue(selectSql.contains("FROM ACCOUNT_DISPATCH_TIME"));
        assertTrue(selectSql.contains("WHERE ACCOUNT_ID = #{ACCOUNTID}"));
        assertTrue(selectSql.contains("ORDER BY SCHEDULE_TIME"));
        assertTrue(normalizedSql(delete.value()).contains(
                "DELETE FROM ACCOUNT_DISPATCH_TIME WHERE ACCOUNT_ID = #{ACCOUNTID}"));
    }

    @Test
    void entityLeavesAuditTimestampsToMysql() throws Exception {
        var entity = new AccountDispatchTimeEntity();

        assertEquals(null, entity.getCreatedAt());
        assertEquals(null, entity.getUpdatedAt());
        assertDatabaseManaged("createdAt");
        assertDatabaseManaged("updatedAt");
    }

    @Test
    void mapperBatchReadsAccountTimesWithoutPerAccountQueries() throws Exception {
        var select = AccountDispatchTimeMapper.class
                .getMethod("selectByAccountIds", Collection.class)
                .getAnnotation(Select.class);

        assertNotNull(select);
        var sql = normalizedSql(select.value());
        assertTrue(sql.contains("FROM ACCOUNT_DISPATCH_TIME"));
        assertTrue(sql.contains("WHERE ACCOUNT_ID IN"));
        assertTrue(sql.contains("ORDER BY ACCOUNT_ID, SCHEDULE_TIME"));
    }

    @Test
    void accountListSerializesEveryScheduleTimeAsHourMinute() {
        var account = new AccountWithSanVO();
        account.setScheduleTimes(List.of(LocalTime.of(8, 0), LocalTime.of(19, 30)));

        var json = new ObjectMapper().findAndRegisterModules().valueToTree(account);

        assertEquals("08:00", json.get("scheduleTimes").get(0).asText());
        assertEquals("19:30", json.get("scheduleTimes").get(1).asText());
    }

    private void assertDatabaseManaged(String fieldName) throws Exception {
        var field = AccountDispatchTimeEntity.class.getDeclaredField(fieldName);
        var annotation = field.getAnnotation(TableField.class);
        assertNotNull(annotation);
        assertEquals(FieldStrategy.NEVER, annotation.insertStrategy());
        assertEquals(FieldStrategy.NEVER, annotation.updateStrategy());
    }

    private String normalizedSql(String[] fragments) {
        return String.join(" ", Arrays.asList(fragments))
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
    }
}
