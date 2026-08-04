package moe.dazecake.inquisition.controller;

import moe.dazecake.inquisition.service.impl.DiscordImageStorage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;

@RestController
public class DiscordImageController {
    private final DiscordImageStorage discordImageStorage;

    public DiscordImageController(DiscordImageStorage discordImageStorage) {
        this.discordImageStorage = discordImageStorage;
    }

    @GetMapping("/media/discord/{messageId}/{attachmentId}")
    public ResponseEntity<Void> redirectToCurrentAttachment(
            @PathVariable String messageId,
            @PathVariable String attachmentId) {
        try {
            var currentUrl = discordImageStorage.resolveAttachmentUrl(messageId, attachmentId);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(currentUrl))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .build();
        } catch (FileNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
