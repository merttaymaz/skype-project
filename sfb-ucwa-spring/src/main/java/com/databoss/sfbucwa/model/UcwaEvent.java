package com.databoss.sfbucwa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UcwaEvent {

    @JsonProperty("_links")
    private Map<String, AutodiscoverResponse.Link> links;

    private List<Sender> sender;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sender {
        private String rel;
        private String href;
        private List<EventData> events;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventData {
        private String link;
        private String type; // added, updated, deleted
        private String status;
        private String rel;
        private String href;

        @JsonProperty("_embedded")
        private Map<String, Object> embedded;

        /**
         * Inline resource (IM mesajı gibi).
         * UCWA bazı event'lerde _embedded içinde kaynağı inline döner.
         */
        @JsonProperty("in")
        private Map<String, Object> inlineResource;
    }
}
