package lld.designpatterns.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable representation of a SELECT query. Built via {@link SelectQuery.Builder}.
 */
public final class SelectQuery {

    private final List<String> columns;
    private final String table;
    private final String whereClause;
    private final String orderByColumn;
    private final boolean orderAsc;
    private final Integer limit;

    private SelectQuery(Builder b) {
        this.columns = b.columns.isEmpty() ? List.of("*") : List.copyOf(b.columns);
        this.table = Objects.requireNonNull(b.table, "table");
        this.whereClause = b.whereClause;
        this.orderByColumn = b.orderByColumn;
        this.orderAsc = b.orderAsc;
        this.limit = b.limit;
    }

    public List<String> getColumns() { return columns; }
    public String getTable() { return table; }
    public String getWhereClause() { return whereClause; }
    public String getOrderByColumn() { return orderByColumn; }
    public boolean isOrderAsc() { return orderAsc; }
    public Integer getLimit() { return limit; }

    /**
     * Returns the SQL string. Escaping is the responsibility of the caller for raw values.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(String.join(", ", columns));
        sb.append(" FROM ").append(table);
        if (whereClause != null && !whereClause.isEmpty()) {
            sb.append(" WHERE ").append(whereClause);
        }
        if (orderByColumn != null && !orderByColumn.isEmpty()) {
            sb.append(" ORDER BY ").append(orderByColumn).append(orderAsc ? " ASC" : " DESC");
        }
        if (limit != null) {
            sb.append(" LIMIT ").append(limit);
        }
        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<String> columns = new ArrayList<>();
        private String table;
        private String whereClause;
        private String orderByColumn;
        private boolean orderAsc = true;
        private Integer limit;

        public Builder select(String... cols) {
            columns.clear();
            for (String c : cols) columns.add(c);
            return this;
        }

        public Builder from(String table) {
            this.table = table;
            return this;
        }

        public Builder where(String condition) {
            this.whereClause = condition;
            return this;
        }

        public Builder orderBy(String column) {
            return orderBy(column, true);
        }

        public Builder orderBy(String column, boolean ascending) {
            this.orderByColumn = column;
            this.orderAsc = ascending;
            return this;
        }

        public Builder limit(int n) {
            if (n < 1) throw new IllegalArgumentException("limit must be positive");
            this.limit = n;
            return this;
        }

        public SelectQuery build() {
            if (table == null || table.isEmpty()) {
                throw new IllegalStateException("table is required");
            }
            return new SelectQuery(this);
        }
    }
}
