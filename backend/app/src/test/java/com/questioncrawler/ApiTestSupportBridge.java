package com.questioncrawler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** Public accessor so tests in other packages can reuse the package-private support class. */
public final class ApiTestSupportBridge {

    private ApiTestSupportBridge() {}

    public static String config(Path dir) {
        try {
            return ApiTestSupport.writeConfig(dir).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
