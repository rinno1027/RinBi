package org.rin.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.rin.report.entity.ReportFileEntity;

/**
 * 智能报表生成文件访问入口。
 */
@Mapper
public interface ReportFileMapper extends BaseMapper<ReportFileEntity> {
}
