package ru.bmstu.sqlfornosql.adapters.postgres;

import ru.bmstu.sqlfornosql.adapters.sql.SqlHolder;
import ru.bmstu.sqlfornosql.model.Row;
import ru.bmstu.sqlfornosql.model.RowType;
import ru.bmstu.sqlfornosql.model.Table;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.sql.Types.*;

public class PostgresMapper {
    public Table mapResultSet(ResultSet resultSet, SqlHolder query) {
        Table table = new Table();
        try (resultSet){
            ResultSetMetaData metaData = resultSet.getMetaData();
            while (resultSet.next()) {
                Row row = new Row(table);
                Map<String, RowType> typeMap = new LinkedHashMap<>();
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    RowType type = getTypeFromSqlType(metaData.getColumnType(i));
                    if (!query.isSelectAll()) {
                        String column = query.getQualifiedNamesMap().get(metaData.getColumnName(i));
                        row.add(column, getPostgresValue(resultSet, i, type));
                        typeMap.put(column, type);
                    } else {
                        String column = query.getDatabase().toString() + "." + metaData.getColumnName(i);
                        row.add(column, getPostgresValue(resultSet, i, type));
                        typeMap.put(column, type);
                    }
                }
                table.add(row, typeMap);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Can't get result of query", e);
        }

        return table;
    }

    private RowType getTypeFromSqlType(int type) {
        switch (type) {
            case BIT:
            case TINYINT:
            case BIGINT:
            case INTEGER:
            case SMALLINT:
                return RowType.INT;
            case LONGNVARCHAR:
            case CHAR:
            case VARCHAR:
                return RowType.STRING;
            case NULL:
                return RowType.NULL;
            case NUMERIC:
            case DECIMAL:
            case FLOAT:
            case DOUBLE:
            case REAL:
                return RowType.DOUBLE;
            case DATE:
            case TIME:
            case TIMESTAMP:
                return RowType.DATE;
            default:
                throw new IllegalArgumentException("Type unsupported");
        }
    }

    private Object getPostgresValue(ResultSet resultSet, int column, RowType type) {
        try {
            switch (type) {
                case INT:
                    return resultSet.getInt(column);
                case DOUBLE:
                    return resultSet.getDouble(column);
                case DATE:
                    return resultSet.getTimestamp(column).toLocalDateTime();
                case BOOLEAN:
                    return resultSet.getBoolean(column);
                case STRING:
                    return resultSet.getString(column);
                case NULL:
                    return null;
                default:
                    throw new IllegalStateException("Wrong type: " + type);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Can't get value", e);
        }
    }
}
