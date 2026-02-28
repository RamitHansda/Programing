package interview.yipitdata;

import java.sql.Connection;

public interface ConnectionPool {
    Connection getConnection() throws Exception;
}
