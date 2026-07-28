package moe.dazecake.inquisition.mapper.mapstruct;

import moe.dazecake.inquisition.model.dto.account.AccountDTO;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.vo.account.AccountWithSanVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface AccountConvert {

    AccountConvert INSTANCE = Mappers.getMapper(AccountConvert.class);

    @Mapping(target = "todayLoginCount", ignore = true)
    @Mapping(target = "dispatchMode", ignore = true)
    @Mapping(target = "scheduleTime", ignore = true)
    @Mapping(target = "nextScheduledAt", ignore = true)
    @Mapping(target = "scheduleStatus", ignore = true)
    AccountWithSanVO toAccountWithSanVO(AccountEntity accountEntity, String san);

    AccountEntity toAccountEntity(AccountDTO accountDTO);

    @Mapping(target = "assignmentId", ignore = true)
    AccountDTO toAccountDTO(AccountEntity accountEntity);
}
