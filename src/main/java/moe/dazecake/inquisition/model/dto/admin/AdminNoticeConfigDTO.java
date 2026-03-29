package moe.dazecake.inquisition.model.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminNoticeConfigDTO {
    private Boolean wxPusherEnable = false;
    private String wxPusherUid = "";
    private Boolean pushPlusEnable = false;
    private String pushPlusToken = "";
}
