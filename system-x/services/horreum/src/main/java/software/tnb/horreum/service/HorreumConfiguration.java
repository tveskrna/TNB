package software.tnb.horreum.service;

import software.tnb.common.config.Configuration;

public class HorreumConfiguration extends Configuration {

    public static final String URL = "horreum.url";
    public static final String TEST_NAME = "horreum.testname";
    public static final String SCHEMA = "horreum.schema";
    public static final String TEST_OWNER = "horreum.testowner";
    public static final String HTTP_LOG_ENABLED = "horreum.http.log.enabled";

    public static String getUrl() {
        return getProperty(URL);
    }

    public static String getTestName() {
        return getProperty(TEST_NAME);
    }

    public static String getSchema() {
        return getProperty(SCHEMA);
    }

    public static String getTestOwner() {
        return getProperty(TEST_OWNER);
    }

    public static boolean isHttpLogEnabled() {
        return getBoolean(HTTP_LOG_ENABLED, false);
    }
}
