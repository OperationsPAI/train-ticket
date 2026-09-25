package com.trainticket.walletpromotion.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuthoritativeBenefitScopeVerifierWiringTest {
    @Test
    void productionConstructorIsExplicitlySelectedForSpringInjection() {
        Constructor<?>[] constructors = AuthoritativeBenefitScopeVerifier.class.getDeclaredConstructors();
        var autowired = Arrays.stream(constructors)
            .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
            .toList();

        assertEquals(1, autowired.size());
        assertTrue(Arrays.stream(autowired.getFirst().getParameterTypes())
            .anyMatch(type -> type.getName().equals("org.springframework.web.client.RestClient$Builder")));
    }
}
