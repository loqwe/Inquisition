package moe.dazecake.inquisition.model.dto.account;

import lombok.Data;
import moe.dazecake.inquisition.model.entity.ActivationDateSet.ActivationDate;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.ConfigEntity;
import moe.dazecake.inquisition.model.entity.NoticeEntitySet.NoticeEntity;

import java.time.LocalDateTime;

@Data
public class AddAccountDTO {

    private String name;

    private String account;

    private String password;

    private Long server;

    private LocalDateTime expireTime;

    private Long agent;

    private Integer freeze;

    private String taskType;

    private Integer refresh;

    private ConfigEntity config;

    private ActivationDate active;

    private NoticeEntity notice;
}
