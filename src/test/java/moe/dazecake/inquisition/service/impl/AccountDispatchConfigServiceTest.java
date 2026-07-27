package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.ObjectMapper;
import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.model.dto.account.AccountDispatchConfigDTO;
import moe.dazecake.inquisition.model.entity.AccountDispatchConfigEntity;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.ActivationDateSet.ActivateConfig;
import moe.dazecake.inquisition.model.entity.ActivationDateSet.ActivationDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountDispatchConfigServiceTest {

    private AccountDispatchConfigMapper configMapper;
    private AccountDispatchConfigService service;

    @BeforeEach
    void setUp() {
        configMapper = mock(AccountDispatchConfigMapper.class);
        service = new AccountDispatchConfigService();
        service.configMapper = configMapper;
        service.calculator = new AccountScheduleCalculator();
    }

    @Test
    void dtoContainsOnlyDispatchModeAndScheduleTimeAndParsesHourMinute() throws Exception {
        Set<String> fields = Arrays.stream(AccountDispatchConfigDTO.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());
        var request = new ObjectMapper().findAndRegisterModules().readValue(
                "{\"dispatchMode\":\"SCHEDULED\",\"scheduleTime\":\"19:30\"}",
                AccountDispatchConfigDTO.class);

        assertEquals(Set.of("dispatchMode", "scheduleTime"), fields);
        assertEquals(AccountDispatchConfigService.SCHEDULED, request.getDispatchMode());
        assertEquals(LocalTime.of(19, 30), request.getScheduleTime());
    }

    @Test
    void nullableScheduleFieldsAreAlwaysUpdatedWhileAuditFieldsRemainDatabaseManaged()
            throws Exception {
        assertUpdateStrategy("scheduleTime", FieldStrategy.IGNORED);
        assertUpdateStrategy("nextScheduledAt", FieldStrategy.IGNORED);
        assertAuditStrategies("createdAt");
        assertAuditStrategies("updatedAt");
    }

    @Test
    void configurationWritesAreTransactional() {
        var update = requireMethod(AccountDispatchConfigService.class, "update",
                AccountEntity.class, AccountDispatchConfigDTO.class,
                boolean.class, LocalDateTime.class);
        var activatePending = requireMethod(AccountDispatchConfigService.class, "activatePending",
                AccountEntity.class, LocalDateTime.class);

        assertNotNull(update.getAnnotation(Transactional.class));
        assertNotNull(activatePending.getAnnotation(Transactional.class));
    }

    @Test
    void mapperDefinesAtomicUpsertLockedReadAndNarrowPendingUpdate() {
        var upsert = requireMethod(AccountDispatchConfigMapper.class, "upsert",
                AccountDispatchConfigEntity.class);
        var selectForUpdate = requireMethod(AccountDispatchConfigMapper.class, "selectByIdForUpdate",
                Long.class);
        var completePending = requireMethod(AccountDispatchConfigMapper.class,
                "completePendingActivation", Long.class, LocalTime.class, LocalDateTime.class);

        var insert = upsert.getAnnotation(Insert.class);
        var select = selectForUpdate.getAnnotation(Select.class);
        var update = completePending.getAnnotation(Update.class);
        assertNotNull(insert);
        assertNotNull(select);
        assertNotNull(update);

        var upsertSql = normalizedSql(insert.value());
        assertTrue(upsertSql.contains("INSERT INTO ACCOUNT_DISPATCH_CONFIG"));
        assertTrue(upsertSql.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(upsertSql.contains("UPDATED_AT = CURRENT_TIMESTAMP(6)"));
        assertTrue(upsertSql.contains("SCHEDULE_TIME"));
        assertTrue(upsertSql.contains("NEXT_SCHEDULED_AT"));

        assertTrue(normalizedSql(select.value()).contains("FOR UPDATE"));
        var pendingSql = normalizedSql(update.value());
        assertTrue(pendingSql.contains("ACTIVATION_PENDING = 0"));
        assertTrue(pendingSql.contains("ACTIVATION_PENDING = 1"));
        assertTrue(pendingSql.contains("UPDATED_AT = CURRENT_TIMESTAMP(6)"));
    }

    @Test
    void missingConfigurationDefaultsToAutoWithoutWriting() {
        when(configMapper.selectById(398L)).thenReturn(null);

        var config = service.getOrDefault(398L);

        assertEquals(398L, config.getAccountId());
        assertEquals(AccountDispatchConfigService.AUTO, config.getDispatchMode());
        assertEquals(0, config.getActivationPending());
        assertNull(config.getScheduleTime());
        assertNull(config.getNextScheduledAt());
        assertTrue(service.isAuto(398L));
        verify(configMapper, times(2)).selectById(398L);
        verify(configMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(configMapper, never()).updateById(org.mockito.ArgumentMatchers.any());
        verify(configMapper, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isAutoReturnsFalseForScheduledConfiguration() {
        when(configMapper.selectById(398L)).thenReturn(new AccountDispatchConfigEntity()
                .setAccountId(398L)
                .setDispatchMode(AccountDispatchConfigService.SCHEDULED));

        assertFalse(service.isAuto(398L));
    }

    @Test
    void rejectsUnknownDispatchMode() {
        var request = request("MANUAL", LocalTime.of(19, 30));

        assertThrows(IllegalArgumentException.class,
                () -> service.update(accountWithActiveMonday(), request, false, mondayMorning()));
        verifyNoInteractions(configMapper);
    }

    @Test
    void scheduledModeRequiresATime() {
        var request = request(AccountDispatchConfigService.SCHEDULED, null);

        assertThrows(IllegalArgumentException.class,
                () -> service.update(accountWithActiveMonday(), request, false, mondayMorning()));
        verifyNoInteractions(configMapper);
    }

    @Test
    void scheduledModeRequiresAnEnabledWeekdayEvenDuringAnActiveAssignment() {
        var request = request(AccountDispatchConfigService.SCHEDULED, LocalTime.of(19, 30));

        assertThrows(IllegalArgumentException.class,
                () -> service.update(accountWithoutEnabledWeekdays(), request, true, mondayMorning()));
        verifyNoInteractions(configMapper);
    }

    @Test
    void upsertsScheduledConfigurationWithTheNextFutureOccurrence() {
        var account = accountWithActiveMonday();
        when(configMapper.upsert(any(AccountDispatchConfigEntity.class))).thenReturn(1);

        service.update(account,
                request(AccountDispatchConfigService.SCHEDULED, LocalTime.of(19, 30)),
                false, mondayMorning());

        var captor = ArgumentCaptor.forClass(AccountDispatchConfigEntity.class);
        verify(configMapper).upsert(captor.capture());
        verify(configMapper, never()).selectById(account.getId());
        var saved = captor.getValue();
        assertEquals(account.getId(), saved.getAccountId());
        assertEquals(AccountDispatchConfigService.SCHEDULED, saved.getDispatchMode());
        assertEquals(LocalTime.of(19, 30), saved.getScheduleTime());
        assertEquals(LocalDateTime.of(2026, 7, 27, 19, 30), saved.getNextScheduledAt());
        assertEquals(0, saved.getActivationPending());
        assertNull(saved.getCreatedAt());
        assertNull(saved.getUpdatedAt());
    }

    @Test
    void activeAssignmentDefersScheduledActivationThroughAtomicUpsert() {
        var account = accountWithActiveMonday();
        when(configMapper.upsert(any(AccountDispatchConfigEntity.class))).thenReturn(1);

        service.update(account,
                request(AccountDispatchConfigService.SCHEDULED, LocalTime.of(20, 0)),
                true, mondayMorning());

        var captor = ArgumentCaptor.forClass(AccountDispatchConfigEntity.class);
        verify(configMapper).upsert(captor.capture());
        verify(configMapper, never()).selectById(account.getId());
        var saved = captor.getValue();
        assertEquals(AccountDispatchConfigService.SCHEDULED, saved.getDispatchMode());
        assertEquals(LocalTime.of(20, 0), saved.getScheduleTime());
        assertNull(saved.getNextScheduledAt());
        assertEquals(1, saved.getActivationPending());
        assertNull(saved.getCreatedAt());
        assertNull(saved.getUpdatedAt());
    }

    @Test
    void switchingToAutoClearsSchedulingStateImmediatelyWithoutAnAssignment() {
        var account = accountWithActiveMonday();
        when(configMapper.upsert(any(AccountDispatchConfigEntity.class))).thenReturn(1);

        service.update(account,
                request(AccountDispatchConfigService.AUTO, LocalTime.of(8, 0)),
                false, mondayMorning());

        var captor = ArgumentCaptor.forClass(AccountDispatchConfigEntity.class);
        verify(configMapper).upsert(captor.capture());
        var saved = captor.getValue();
        assertEquals(AccountDispatchConfigService.AUTO, saved.getDispatchMode());
        assertNull(saved.getScheduleTime());
        assertNull(saved.getNextScheduledAt());
        assertEquals(0, saved.getActivationPending());
    }

    @Test
    void switchingToAutoDuringAnActiveAssignmentMarksActivationPending() {
        var account = accountWithActiveMonday();
        when(configMapper.upsert(any(AccountDispatchConfigEntity.class))).thenReturn(1);

        service.update(account, request(AccountDispatchConfigService.AUTO, null),
                true, mondayMorning());

        var captor = ArgumentCaptor.forClass(AccountDispatchConfigEntity.class);
        verify(configMapper).upsert(captor.capture());
        var saved = captor.getValue();
        assertEquals(AccountDispatchConfigService.AUTO, saved.getDispatchMode());
        assertNull(saved.getScheduleTime());
        assertNull(saved.getNextScheduledAt());
        assertEquals(1, saved.getActivationPending());
    }

    @Test
    void updateFailsWhenAtomicUpsertAffectsNoRows() {
        var account = accountWithActiveMonday();
        when(configMapper.upsert(any(AccountDispatchConfigEntity.class))).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.update(account,
                        request(AccountDispatchConfigService.SCHEDULED, LocalTime.of(19, 30)),
                        false, mondayMorning()));
    }

    @Test
    void activatePendingScheduledConfigurationCalculatesAStrictlyFutureOccurrence() {
        var account = accountWithActiveMonday();
        var existing = scheduledConfig(account.getId())
                .setNextScheduledAt(null)
                .setActivationPending(1);
        var next = LocalDateTime.of(2026, 8, 3, 19, 30);
        when(configMapper.selectByIdForUpdate(account.getId())).thenReturn(existing);
        when(configMapper.completePendingActivation(account.getId(), LocalTime.of(19, 30), next))
                .thenReturn(1);

        service.activatePending(account, LocalDateTime.of(2026, 7, 27, 19, 30));

        verify(configMapper).selectByIdForUpdate(account.getId());
        verify(configMapper).completePendingActivation(account.getId(), LocalTime.of(19, 30), next);
        verify(configMapper, never()).updateById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activatePendingAutoConfigurationClearsStaleSchedulingState() {
        var account = accountWithActiveMonday();
        var existing = new AccountDispatchConfigEntity()
                .setAccountId(account.getId())
                .setDispatchMode(AccountDispatchConfigService.AUTO)
                .setScheduleTime(LocalTime.of(19, 30))
                .setNextScheduledAt(LocalDateTime.of(2026, 8, 3, 19, 30))
                .setActivationPending(1);
        when(configMapper.selectByIdForUpdate(account.getId())).thenReturn(existing);
        when(configMapper.completePendingActivation(account.getId(), null, null)).thenReturn(1);

        service.activatePending(account, mondayMorning());

        verify(configMapper).selectByIdForUpdate(account.getId());
        verify(configMapper).completePendingActivation(account.getId(), null, null);
        verify(configMapper, never()).updateById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activatePendingFailsWhenNarrowUpdateAffectsNoRows() {
        var account = accountWithActiveMonday();
        var existing = scheduledConfig(account.getId()).setActivationPending(1);
        var next = LocalDateTime.of(2026, 7, 27, 19, 30);
        when(configMapper.selectByIdForUpdate(account.getId())).thenReturn(existing);
        when(configMapper.completePendingActivation(account.getId(), LocalTime.of(19, 30), next))
                .thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.activatePending(account, mondayMorning()));
    }

    @Test
    void activatePendingReportsCorruptPersistedModeAsStateFailure() {
        var account = accountWithActiveMonday();
        var existing = scheduledConfig(account.getId())
                .setDispatchMode("BROKEN")
                .setActivationPending(1);
        when(configMapper.selectByIdForUpdate(account.getId())).thenReturn(existing);

        assertThrows(IllegalStateException.class,
                () -> service.activatePending(account, mondayMorning()));
        verify(configMapper, never()).completePendingActivation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activatePendingReportsMissingPersistedScheduleTimeAsStateFailure() {
        var account = accountWithActiveMonday();
        var existing = scheduledConfig(account.getId())
                .setScheduleTime(null)
                .setActivationPending(1);
        when(configMapper.selectByIdForUpdate(account.getId())).thenReturn(existing);

        assertThrows(IllegalStateException.class,
                () -> service.activatePending(account, mondayMorning()));
    }

    @Test
    void activatePendingIgnoresConfigurationThatIsNotPending() {
        var account = accountWithActiveMonday();
        var existing = scheduledConfig(account.getId()).setActivationPending(0);
        when(configMapper.selectByIdForUpdate(account.getId())).thenReturn(existing);

        service.activatePending(account, mondayMorning());

        verify(configMapper).selectByIdForUpdate(account.getId());
        verify(configMapper, never()).completePendingActivation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private AccountDispatchConfigDTO request(String mode, LocalTime time) {
        var request = new AccountDispatchConfigDTO();
        request.setDispatchMode(mode);
        request.setScheduleTime(time);
        return request;
    }

    private void assertUpdateStrategy(String fieldName, FieldStrategy expected) throws Exception {
        var field = AccountDispatchConfigEntity.class.getDeclaredField(fieldName);
        var tableField = field.getAnnotation(TableField.class);
        assertNotNull(tableField, fieldName + " must declare an explicit update strategy");
        assertEquals(expected, tableField.updateStrategy());
    }

    private void assertAuditStrategies(String fieldName) throws Exception {
        var field = AccountDispatchConfigEntity.class.getDeclaredField(fieldName);
        var tableField = field.getAnnotation(TableField.class);
        assertNotNull(tableField, fieldName + " must remain database managed");
        assertEquals(FieldStrategy.NEVER, tableField.insertStrategy());
        assertEquals(FieldStrategy.NEVER, tableField.updateStrategy());
    }

    private Method requireMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        var method = Arrays.stream(type.getMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .filter(candidate -> Arrays.equals(candidate.getParameterTypes(), parameterTypes))
                .findFirst()
                .orElse(null);
        assertNotNull(method, type.getSimpleName() + "." + name + " contract is required");
        return method;
    }

    private String normalizedSql(String[] fragments) {
        return String.join(" ", fragments)
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private AccountEntity accountWithActiveMonday() {
        var account = accountWithoutEnabledWeekdays();
        account.getActive().getMonday().setEnable(true);
        return account;
    }

    private AccountEntity accountWithoutEnabledWeekdays() {
        var active = new ActivationDate();
        activationConfigs(active).forEach(config -> config.setEnable(false));
        return new AccountEntity().setId(398L).setActive(active);
    }

    private List<ActivateConfig> activationConfigs(ActivationDate active) {
        return List.of(active.getMonday(), active.getTuesday(), active.getWednesday(),
                active.getThursday(), active.getFriday(), active.getSaturday(), active.getSunday());
    }

    private AccountDispatchConfigEntity scheduledConfig(Long accountId) {
        return new AccountDispatchConfigEntity()
                .setAccountId(accountId)
                .setDispatchMode(AccountDispatchConfigService.SCHEDULED)
                .setScheduleTime(LocalTime.of(19, 30))
                .setNextScheduledAt(LocalDateTime.of(2026, 8, 3, 19, 30))
                .setActivationPending(0);
    }

    private LocalDateTime mondayMorning() {
        return LocalDateTime.of(2026, 7, 27, 10, 0);
    }
}
