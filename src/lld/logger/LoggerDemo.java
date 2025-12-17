package lld.logger;


public class LoggerDemo {

    public static void main(String[] args) {
        Logger logger = LoggerFactory.createAsyncLogger();

        logger.info("Application started");
        logger.debug("Debugging enabled");
        logger.warn("Low disk space");
        logger.error("Something went wrong");

        logger.shutdown();
    }
}