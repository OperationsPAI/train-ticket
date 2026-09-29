package com.trainticket.financesettlement.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.trainticket.financesettlement.ServiceSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListingPageCheckTest {
    @TempDir
    Path directory;

    @Test
    void aListingServesTheBoundItResolvedAndRefusesThePagePastIt() {
        FinanceSettlementApplicationService service = service();
        int bound = ServiceSettings.defaults().listingPageBound();

        assertDoesNotThrow(() -> service.listReconciliationCases("ord-1", bound, 0));
        assertDoesNotThrow(() -> service.listBenefitCosts("acc-1", bound, 0));
        assertDoesNotThrow(() -> service.listAncillaryFinancialFacts("jor-1", bound, 0));

        ValidationException refused = assertThrows(ValidationException.class,
            () -> service.listReconciliationCases("ord-1", bound + 1, 0));
        assertEquals("limit must be between 1 and " + bound, refused.getMessage());
    }

    @Test
    void aDeploymentMovesThePageItsListingsServe() throws IOException {
        Path file = directory.resolve("read-paths.properties");
        Files.writeString(file, "read.listing.pageBound = 5\n");
        FinanceSettlementApplicationService service = service().withServiceSettings(ServiceSettings.load(file));

        assertEquals(5, service.listReconciliationCases("ord-1", 5, 0).limit());
        assertThrows(ValidationException.class, () -> service.listReconciliationCases("ord-1", 6, 0));
    }

    private static FinanceSettlementApplicationService service() {
        return new FinanceSettlementApplicationService(
            new InMemoryRevenueRepository(),
            new InMemoryReconciliationRepository(),
            new InMemoryInvoiceRepository(),
            event -> { },
            new DomainEventEnvelopeMapper(),
            Clock.systemUTC());
    }
}
