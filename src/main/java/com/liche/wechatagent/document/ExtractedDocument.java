package com.liche.wechatagent.document;

import java.util.List;

public record ExtractedDocument(String name, String text, List<String> pageImages, boolean truncated) {
}
