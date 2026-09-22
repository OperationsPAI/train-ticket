package com.trainticket.journeyorder.infrastructure.offer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named="TEST_OFFER_URL",matches=".+")
class LiveOfferPriceTest {
    @Test
    void missingOffersAndStaleVersionsFailWithoutSubstitutingAPrice() {
        var adapter = new HttpOfferPriceAdapter(new ObjectMapper(),RestClient.builder(),System.getenv("TEST_OFFER_URL"));
        var missing = assertThrows(ApiException.class, () -> adapter.totalFor("off-"+UUID.randomUUID(),1));
        assertEquals(ApiErrorCode.NOT_FOUND, missing.code());
        String offer = System.getenv("TEST_OFFER_ID");
        var stale = assertThrows(ApiException.class, () -> adapter.totalFor(offer,Integer.MAX_VALUE));
        assertEquals(ApiErrorCode.PRECONDITION_FAILED,stale.code());
    }
}
