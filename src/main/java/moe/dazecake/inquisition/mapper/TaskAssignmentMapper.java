package moe.dazecake.inquisition.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import moe.dazecake.inquisition.model.entity.TaskAssignmentEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskAssignmentMapper extends BaseMapper<TaskAssignmentEntity> {
}
