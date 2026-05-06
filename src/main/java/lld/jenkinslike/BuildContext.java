package lld.jenkinslike;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime context passed to every build step.
 */
public class BuildContext {
    private final String jobName;
    private final long buildNumber;
    private final Map<String, String> environment;
    private final BuildLogger logger;

    BuildContext(String jobName, long buildNumber, Map<String, String> environment, BuildLogger logger) {
        this.jobName = jobName;
        this.buildNumber = buildNumber;
        this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        this.logger = logger;
    }

    public String getJobName() {
        return jobName;
    }

    public long getBuildNumber() {
        return buildNumber;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public String getEnv(String key) {
        return environment.get(key);
    }

    public void log(String message) {
        logger.log(message);
    }
}
