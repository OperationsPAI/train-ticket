package com.trainticket.financesettlement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceSettingsTest {
    @TempDir
    Path directory;

    @Test
    void absentFileKeepsTheCompiledDefaults() {
        ServiceSettings settings = ServiceSettings.load(directory.resolve("read-paths.properties"));

        assertEquals(ServiceSettings.DEFAULT_LISTING_PAGE_BOUND, settings.listingPageBound());
        assertEquals(ServiceSettings.DEFAULT_PLATFORM_REFUND_PAGE_BOUND, settings.platformRefundPageBound());
        assertEquals(ServiceSettings.DEFAULT_CHANNEL_STATEMENT_PAGE_BOUND, settings.channelStatementPageBound());
    }

    @Test
    void aFileMovesTheBoundsTheProcessServes() throws IOException {
        Path file = directory.resolve("read-paths.properties");
        Files.writeString(file, """
            read.listing.pageBound = 50
            read.platformRefund.pageBound = 20
            read.channelStatement.pageBound = 30
            """);

        ServiceSettings settings = ServiceSettings.load(file);

        assertEquals(50, settings.listingPageBound());
        assertEquals(20, settings.platformRefundPageBound());
        assertEquals(30, settings.channelStatementPageBound());
    }

    @Test
    void aValueThatIsNotAPositiveCountKeepsItsDefault() throws IOException {
        Path file = directory.resolve("read-paths.properties");
        Files.writeString(file, "read.listing.pageBound = not-a-number\nread.platformRefund.pageBound = 0\n");

        ServiceSettings settings = ServiceSettings.load(file);

        assertEquals(ServiceSettings.DEFAULT_LISTING_PAGE_BOUND, settings.listingPageBound());
        assertEquals(ServiceSettings.DEFAULT_PLATFORM_REFUND_PAGE_BOUND, settings.platformRefundPageBound());
    }
}
