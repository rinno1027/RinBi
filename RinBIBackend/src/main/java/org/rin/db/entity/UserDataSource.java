package org.rin.db.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户与数据源的授权关系，对应元数据库 rinbidb.user_data_source。
 *
 * <p>中间表只保存归属关系，连接配置统一存放在 data_source。
 * 当前登录用户只能看到并访问这里为其授权的数据源。</p>
 */
@Data
@TableName("user_data_source")
public class UserDataSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long dataSourceId;

    private LocalDateTime createdAt;
}

