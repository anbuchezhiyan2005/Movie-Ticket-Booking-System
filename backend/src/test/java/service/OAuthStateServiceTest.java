package service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OAuthStateServiceTest {

    @Test
    void createsRfc7636S256CodeChallenge() {
        OAuthStateService stateService = new OAuthStateService();

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                stateService.createCodeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }
}