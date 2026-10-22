package com.liche.qqagent.channel;

import java.nio.file.Path;

public record OutboundMedia(Path localFile, String fileName, String contentType) {
}
