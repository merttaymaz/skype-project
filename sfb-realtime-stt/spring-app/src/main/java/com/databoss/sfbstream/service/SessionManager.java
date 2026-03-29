package com.databoss.sfbstream.service;

import com.databoss.sfbstream.model.AudioSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SessionManager {

    private final Map<String, AudioSession> sessions = new ConcurrentHashMap<>();

    public AudioSession createSession(String sessionId, String conferenceUri,
                                       int sampleRate, int channels, int bitsPerSample) {
        var session = new AudioSession(sessionId, conferenceUri, sampleRate, channels, bitsPerSample);
        sessions.put(sessionId, session);
        log.info("Aktif session sayisi: {}", sessions.size());
        return session;
    }

    public AudioSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public AudioSession removeSession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
            log.info("Session kaldirildi: {}. Kalan: {}", sessionId, sessions.size());
        }
        return session;
    }

    public Collection<AudioSession> getActiveSessions() {
        return sessions.values();
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    public Map<String, Object> getStats() {
        long totalBytes = sessions.values().stream()
                .mapToLong(AudioSession::getTotalBytesReceived).sum();
        long totalSegments = sessions.values().stream()
                .mapToLong(s -> s.getTranscriptSegments().size()).sum();
        return Map.of(
                "activeSessions", sessions.size(),
                "totalBytesReceived", totalBytes,
                "totalTranscriptSegments", totalSegments
        );
    }
}
