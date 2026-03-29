package com.databoss.sfbucwa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContactSearchResult {

    @JsonProperty("_embedded")
    private Embedded embedded;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Embedded {
        private List<Contact> contact;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contact {
        private String uri;
        private String name;
        private String emailAddresses;
        private String company;
        private String department;
        private String title;

        @JsonProperty("_links")
        private Map<String, AutodiscoverResponse.Link> links;
    }
}
