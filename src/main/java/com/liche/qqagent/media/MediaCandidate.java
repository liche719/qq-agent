package com.liche.qqagent.media;

public record MediaCandidate(int index, String originalName, String contentType, String sourceUrl,
                             String extractedText, boolean image) {
}
