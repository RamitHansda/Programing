package interview.yipitdata;

import java.sql.Connection;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DBConnectionPool implements ConnectionPool {

    ConcurrentLinkedDeque<Connection> availableConnection;
    ConcurrentLinkedDeque<Connection> usedConnection;


//    "Dev", 1
//
//
//            "Dev", 2
//
//            "Dev", 4

    DBConnectionPool(int n){
        ExecutorService es =   Executors.newFixedThreadPool(n);
        es.submit(()->{
                    System.out.println("ramit");
        });

        this.usedConnection = new ConcurrentLinkedDeque<>();
        this.availableConnection = new ConcurrentLinkedDeque<>();
    }

//    3GHz
//    3M

    // post {
    //id:
    // type: Vedio|Test|Image
    // url: s3Url
    // user: User
    // timestamp
    // text: string
    // }

    // comment
    //{
    //id:
    // post_id:
    // text:string
    // comment_user:User
    // timestamp
    // }

    // reply
    // id:
    // comment_id,
    // repying_user:User
    // text: String
    // timestamp





    @Override
    public Connection getConnection() throws Exception {
        if(availableConnection.isEmpty())
            throw new Exception("No conenction available");
        else{
            Connection connection = availableConnection.getFirst();
            availableConnection.removeFirst();
            usedConnection.add(connection);
            return connection;
        }
    }

    public void releaseConnection(Connection connection){
        if(usedConnection.contains(connection)){
            usedConnection.remove(connection);
            availableConnection.add(connection);
        }
    }
}
