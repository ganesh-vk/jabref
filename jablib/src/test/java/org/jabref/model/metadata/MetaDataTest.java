package org.jabref.model.metadata;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetaDataTest {

    private MetaData metaData;

    @BeforeEach
    void setUp() {
        metaData = new MetaData();
    }

    @Test
    void emptyGroupsIfNotSet() {
        assertEquals(Optional.empty(), metaData.getGroups());
    }

    @Test
    void getLatexFileDirectoryReturnsEmptyWhenNotSet() {
        assertEquals(Optional.empty(), metaData.getLatexFileDirectory("user-host"));
    }

    @Test
    void storesWebDavFileDirectoryPerUserHost() {
        metaData.setWebDavFileDirectory("user-host", "/mnt/webdav");

        assertEquals(Optional.of("/mnt/webdav"), metaData.getWebDavFileDirectory("user-host"));
        assertEquals(Optional.empty(), metaData.getWebDavFileDirectory("other-host"));
    }

    @Test
    void clearsWebDavFileDirectoryPerUserHost() {
        metaData.setWebDavFileDirectory("user-host", "/mnt/webdav");
        metaData.clearWebDavFileDirectory("user-host");

        assertEquals(Optional.empty(), metaData.getWebDavFileDirectory("user-host"));
    }

    @Test
    void storesAiLibraryId() {
        metaData.setAiLibraryId("test-ai-library-id");

        assertEquals(Optional.of("test-ai-library-id"), metaData.getAiLibraryId());
    }
}
