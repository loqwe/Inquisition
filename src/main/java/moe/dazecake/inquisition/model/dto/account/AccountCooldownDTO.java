package moe.dazecake.inquisition.model.dto.account;

import lombok.Data;

@Data
public class AccountCooldownDTO {
    private Long id;
    private String freezeUntil;
}
