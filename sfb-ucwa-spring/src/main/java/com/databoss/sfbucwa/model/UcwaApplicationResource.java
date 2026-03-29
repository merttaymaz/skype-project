package com.databoss.sfbucwa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UcwaApplicationResource {

    @JsonProperty("_links")
    private Map<String, AutodiscoverResponse.Link> links;

    @JsonProperty("_embedded")
    private EmbeddedResources embedded;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbeddedResources {

        private People people;
        private Communication communication;
        private Me me;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class People {
            @JsonProperty("_links")
            private Map<String, AutodiscoverResponse.Link> links;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Communication {
            @JsonProperty("_links")
            private Map<String, AutodiscoverResponse.Link> links;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Me {
            private String name;
            private String uri;
            @JsonProperty("_links")
            private Map<String, AutodiscoverResponse.Link> links;
        }
    }
}
