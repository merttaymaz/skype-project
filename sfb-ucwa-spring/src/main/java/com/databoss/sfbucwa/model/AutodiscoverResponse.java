package com.databoss.sfbucwa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AutodiscoverResponse {

    @JsonProperty("_links")
    private Map<String, Link> links;

    /**
     * UCWA user resource URL'sini döndürür.
     * Autodiscover yanıtında "_links.user.href" alanı.
     */
    public String getUserResourceUrl() {
        if (links != null && links.containsKey("user")) {
            return links.get("user").getHref();
        }
        return null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Link {
        private String href;
        private String revision;
    }
}
