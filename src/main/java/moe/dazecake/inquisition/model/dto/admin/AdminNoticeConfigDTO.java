package moe.dazecake.inquisition.model.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminNoticeConfigDTO {
    private Boolean mailEnable = false;
    private String adminMail = "";
    private String summarySchedule = "00:00 / 08:00 / 12:00 / 16:00 / 18:00";
    private Boolean wxPusherEnable = false;
    private String wxPusherUid = "";
    private Boolean pushPlusEnable = false;
    private String pushPlusToken = "";
}
