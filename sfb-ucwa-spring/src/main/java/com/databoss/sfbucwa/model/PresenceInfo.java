package com.databoss.sfbucwa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PresenceInfo {
    private String sipUri;
    private String displayName;
    private String availability; // Online, Away, Busy, DoNotDisturb, Offline
    private String activity;     // Available, InACall, InAMeeting, etc.
    private String deviceType;
    private String note;
}
