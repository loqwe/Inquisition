package moe.dazecake.inquisition.model.vo.device;

import lombok.Data;

import java.util.ArrayList;

@Data
public class LoadDeviceVO {
    private ArrayList<LoadDevice> loadDeviceList = new ArrayList<>();
    private ArrayList<LoadDevice> importantDeviceList = new ArrayList<>();
    private ArrayList<LoadDevice> backupDeviceList = new ArrayList<>();
}
