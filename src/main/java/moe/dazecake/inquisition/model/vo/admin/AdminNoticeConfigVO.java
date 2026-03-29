package moe.dazecake.inquisition.model.vo.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminNoticeConfigVO {
    private Boolean wxPusherEnable = false;
    private String wxPusherUid = "";
    private Boolean pushPlusEnable = false;
    private String pushPlusToken = "";
    private Boolean mailEnable = false;
    private String adminMail = "";
    private String summarySchedule = "00:00 / 08:00 / 12:00 / 16:00 / 18:00";
}
