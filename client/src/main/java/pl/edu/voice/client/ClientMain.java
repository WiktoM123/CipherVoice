package pl.edu.voice.client;

import javafx.application.Application;

/** Launcher kept separate from Application so the shaded JAR starts reliably. */
public final class ClientMain {
    private ClientMain() {
    }

    public static void main(String[] args) {
        Application.launch(VoiceChatApplication.class, args);
    }
}

