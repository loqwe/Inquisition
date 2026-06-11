package moe.dazecake.inquisition.utils;

import com.google.gson.Gson;
import moe.dazecake.inquisition.model.entity.ConfigEntitySet.ConfigEntity;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(ConfigEntity.class)
@MappedJdbcTypes({JdbcType.VARCHAR, JdbcType.LONGVARCHAR, JdbcType.OTHER})
public class ConfigEntityTypeHandler extends BaseTypeHandler<ConfigEntity> {

    private static final Gson GSON = new Gson();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, ConfigEntity parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, GSON.toJson(parameter));
    }

    @Override
    public ConfigEntity getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public ConfigEntity getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public ConfigEntity getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private ConfigEntity parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return new ConfigEntity();
        }
        try {
            var config = GSON.fromJson(json, ConfigEntity.class);
            return config == null ? new ConfigEntity() : config;
        } catch (RuntimeException e) {
            throw new SQLException("Failed to parse account config JSON", e);
        }
    }
}
