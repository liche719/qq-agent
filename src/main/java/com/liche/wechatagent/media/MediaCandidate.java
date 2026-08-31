package com.liche.wechatagent.media;

public record MediaCandidate(int index, String originalName, String contentType, String sourceUrl,
                             String extractedText, boolean image) {
}
