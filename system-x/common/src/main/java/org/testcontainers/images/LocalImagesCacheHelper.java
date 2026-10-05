package org.testcontainers.images;

import org.testcontainers.utility.DockerImageName;

public final class LocalImagesCacheHelper {

    private LocalImagesCacheHelper() {
    }

    public static void invalidate(String dockerImageName) {
        LocalImagesCache.INSTANCE.refreshCache(DockerImageName.parse(dockerImageName));
    }
}
