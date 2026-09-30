package moe.dazecake.inquisition.model.dto.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.ActivationDateSet.ActivationDate;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.ConfigEntity;
import moe.dazecake.inquisition.model.entity.NoticeEntitySet.NoticeEntity;

@Data
public class AccountDTO extends AccountEntity {
    @JsonIgnore
    private boolean namePresent;
    @JsonIgnore
    private boolean configPresent;
    @JsonIgnore
    private boolean activePresent;
    @JsonIgnore
    private boolean noticePresent;

    @Override
    @JsonSetter("name")
    public AccountDTO setName(String name) {
        namePresent = true;
        super.setName(name);
        return this;
    }

    @Override
    @JsonSetter("config")
    public AccountDTO setConfig(ConfigEntity config) {
        configPresent = true;
        super.setConfig(config);
        return this;
    }

    @Override
    @JsonSetter("active")
    public AccountDTO setActive(ActivationDate active) {
        activePresent = true;
        super.setActive(active);
        return this;
    }

    @Override
    @JsonSetter("notice")
    public AccountDTO setNotice(NoticeEntity notice) {
        noticePresent = true;
        super.setNotice(notice);
        return this;
    }
}
