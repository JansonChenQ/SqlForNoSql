package ru.bmstu.sqlfornosql.executor;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mongodb.client.MongoDatabase;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SubSelect;
import org.bson.BsonDocument;
import ru.bmstu.sqlfornosql.adapters.mongo.MongoAdapter;
import ru.bmstu.sqlfornosql.adapters.mongo.MongoClient;
import ru.bmstu.sqlfornosql.adapters.postgres.PostgresClient;
import ru.bmstu.sqlfornosql.adapters.sql.SqlHolder;
import ru.bmstu.sqlfornosql.adapters.sql.SqlUtils;
import ru.bmstu.sqlfornosql.model.Table;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Executor {
    static final Pattern IDENT_REGEXP = Pattern.compile("([a-zA-Z]+[0-9a-zA-Z.]*)");

    static final Set<String> FORBIDDEN_STRINGS = ImmutableSet.of(
            "SELECT",
            "AND",
            "IN",
            "BETWEEN",
            "NOT",
            "IS",
            "DATE",
            "TIME",
            "TIMESTAMP",
            "LIKE",
            "TRUE",
            "FALSE",
            "NULL"
    );

    public Table execute(String sql) {
        SqlHolder sqlHolder = SqlUtils.fillSqlMeta(sql);
        if (sqlHolder.getJoins().isEmpty()) {
            return simpleSelect(sqlHolder);
        } else {
            return selectWithJoins(sqlHolder);
        }
    }

    /**
     * Выполняет SELECT запрос без JOIN'ов и с одной таблицей
     * @param sqlHolder - холдер, содержащий запрос
     * @return таблицу с результатом выполнения запроса
     */
    //TODO здесь fromItem может быть подзапросом - это нужно обработать
    private Table simpleSelect(SqlHolder sqlHolder) {
        if (sqlHolder.getFromItem() instanceof net.sf.jsqlparser.schema.Table) {
            switch (sqlHolder.getDatabase().getDbType()) {
                case POSTGRES:
                    try (PostgresClient client = new PostgresClient("localhost", 5432, "postgres", "0212", "postgres")) {
                        return client.executeQuery(sqlHolder);
                    }
                case MONGODB:
                    try (com.mongodb.MongoClient client = new com.mongodb.MongoClient()) {
                        MongoDatabase database = client.getDatabase(sqlHolder.getDatabase().getDatabaseName());
                        MongoAdapter adapter = new MongoAdapter();
                        MongoClient<BsonDocument> mongoClient = new MongoClient<>(
                                database.getCollection(sqlHolder.getDatabase().getTable(), BsonDocument.class)
                        );
                        return mongoClient.executeQuery(adapter.translate(sqlHolder));
                    }
                default:
                    throw new IllegalArgumentException("Unknown database type");
            }
        } else if (sqlHolder.getFromItem() instanceof SubSelect) {
            SubSelect subSelect = ((SubSelect) sqlHolder.getFromItem());
            String subSelectStr = subSelect.toString();
            Table subSelectResult;
            if (subSelect.isUseBrackets()) {
                subSelectResult = execute(subSelectStr.substring(1, subSelectStr.length() - 1));
            } else {
                subSelectResult = execute(subSelectStr);
            }

            //TODO merge results
            throw new UnsupportedOperationException("Not implemented yet");
        } else {
            throw new IllegalStateException("Can't determine type of FROM argument");
        }
    }

    /**
     * Выполяняет SELECT запрос из нескольких таблиц и/или с JOIN
     * @param sqlHolder - холдер, содержащий запрос
     * @return таблицу с результато выполнения запроса
     */
    private Table selectWithJoins(SqlHolder sqlHolder) {
        Map<FromItem, Table> resultParts = new LinkedHashMap<>();
        List<String> queries = new ArrayList<>();
        for (Map.Entry<FromItem, List<SelectItem>> fromItemListEntry : sqlHolder.getSelectItemMap().entrySet()) {
            SqlHolder holder = new SqlHolder.SqlHolderBuilder()
                    .withSelectItems(fromItemListEntry.getValue())
                    .withFromItem(fromItemListEntry.getKey())
                    .build();

            String query = holder.toString();

            List<String> queryOrParts = new ArrayList<>();

            Set<String> selectItemsStr = Sets.newHashSet(holder.getSelectIdents());

            if (sqlHolder.getWhereClause() != null) {
                fillIdents(selectItemsStr, queryOrParts, sqlHolder.getWhereClause());

                if (!queryOrParts.isEmpty()) {
                    query += " WHERE " + String.join(" OR ", queryOrParts);
                }
            }

            if (!sqlHolder.getGroupBys().isEmpty()) {
                List<String> groupBys = new ArrayList<>();
                for (String groupByItem : sqlHolder.getGroupBys()) {
                    if (selectItemsStr.contains(groupByItem)) {
                        groupBys.add(groupByItem);
                    }
                }

                if (!groupBys.isEmpty()) {
                    query += " GROUP BY " + String.join(" ,", groupBys);
                }

                if (sqlHolder.getHavingClause() != null) {
                    List<String> havingOrParts = new ArrayList<>();
                    fillIdents(selectItemsStr, havingOrParts, sqlHolder.getHavingClause());

                    if (!havingOrParts.isEmpty()) {
                        query += " HAVING " + String.join(" OR ", havingOrParts);
                    }
                }
            }

            if (!sqlHolder.getOrderByElements().isEmpty()) {
                List<String> orderBys = new ArrayList<>();
                for (OrderByElement orderByItem : sqlHolder.getOrderByElements()) {
                    fillIdents(selectItemsStr, orderBys, orderByItem.toString());
                }

                if (!orderBys.isEmpty()) {
                    query += " ORDER BY " + String.join(" ,", orderBys);
                }
            }

            queries.add(query);

            Table result = execute(query);
            resultParts.put(fromItemListEntry.getKey(), result);
        }

        System.out.println(queries);

        Table from = resultParts.remove(sqlHolder.getFromItem());
        if (from == null) {
            throw new IllegalStateException("FromItem can not be null");
        }

        return Joiner.join(from, Lists.newArrayList(resultParts.values()), sqlHolder.getJoins(), sqlHolder.getWhereClause());
    }

    private void fillIdents(Set<String> selectItemsStr, List<String> orderBys, String str) {
        Matcher matcher = IDENT_REGEXP.matcher(str.replaceAll("'.*'", ""));
        List<String> idents = new ArrayList<>();
        while (matcher.find()) {
            if (!FORBIDDEN_STRINGS.contains(matcher.group(1).toUpperCase())) {
                idents.add(matcher.group(1));
            }
        }

        if (selectItemsStr.containsAll(idents)) {
            orderBys.add(str);
        }
    }

    private void fillIdents(Set<String> selectItemsStr, List<String> whereOrParts, Expression whereClause) {
        String whereExpression = whereClause.toString();
        String[] orParts = whereExpression.split("\\sOR\\s");
        for (String part : orParts) {
            fillIdents(selectItemsStr, whereOrParts, part);
        }
    }
}
