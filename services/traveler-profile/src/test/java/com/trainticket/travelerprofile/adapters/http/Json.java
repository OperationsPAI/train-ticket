package com.trainticket.travelerprofile.adapters.http;

import com.jayway.jsonpath.JsonPath;
import org.springframework.test.web.servlet.MvcResult;

final class Json {
    private Json() {
    }

    static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
