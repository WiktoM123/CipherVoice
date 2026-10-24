package pl.edu.voice.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import pl.edu.voice.common.MemberInfo;
import pl.edu.voice.common.NameRules;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class VoiceChatApplication extends Application {
    private static final double SPEAKING_THRESHOLD = 0.04;
    private static final long SPEAKING_HANGOVER_NANOS = 420_000_000L;

    private final ObservableList<MemberInfo> participants = FXCollections.observableArrayList();
    private final Map<UUID, Long> speakingUntil = new ConcurrentHashMap<>();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private Stage stage;
    private StackPane appRoot;
    private volatile VoiceClient client;
    private volatile long activeGeneration;
    private ListView<MemberInfo> participantList;
    private Label connectionLabel;
    private Label encryptionLabel;
    private Label epochLabel;
    private Label roomSubtitle;
    private Label errorBanner;
    private Label loginErrorLabel;
    private ProgressBar microphoneLevel;
    private ToggleButton muteButton;
    private ToggleButton deafenButton;
    private Timeline speakingAnimation;
    private int speakingPhase;
    private boolean hadSpeakingParticipant;
    private volatile UUID selfId;
    private volatile UUID leaderId;
    private volatile int currentEpoch;
    private volatile boolean encryptionReady;
    private volatile String latestStatus = "Połączono";
    private volatile String pendingError;
    private String connectedHost;
    private String connectedRoom;
    private String connectedNickname;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        appRoot = new StackPane();
        appRoot.getStyleClass().add("app-root");
        Scene scene = new Scene(appRoot, 1100, 720);
        scene.getStylesheets().add(getClass().getResource("/voice.css").toExternalForm());
        stage.setTitle("CipherVoice — bezpieczne rozmowy grupowe");
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.setScene(scene);
        startSpeakingAnimation();
        showLogin(null);
        stage.show();
    }

    private void showLogin(String error) {
        clearRoomReferences();
        VBox hero = new VBox(18);
        hero.getStyleClass().add("login-hero");
        hero.setAlignment(Pos.CENTER_LEFT);

        Label logo = new Label("CV");
        logo.getStyleClass().add("logo-large");
        Label brand = new Label("CipherVoice");
        brand.getStyleClass().add("brand-title");
        Label headline = new Label("Rozmowa należy\ndo uczestników.");
        headline.getStyleClass().add("hero-title");
        Label description = new Label(
                "Grupowy komunikator głosowy z szyfrowaniem end-to-end. "
                        + "Serwer przekazuje pakiety, ale nie zna treści rozmowy.");
        description.getStyleClass().add("hero-description");
        description.setWrapText(true);
        description.setMaxWidth(430);

        HBox securityPill = new HBox(8,
                new Label("●"), new Label("Opus  •  UDP  •  AES-256-GCM"));
        securityPill.getStyleClass().add("security-pill");
        securityPill.setMaxWidth(Region.USE_PREF_SIZE);
        hero.getChildren().addAll(logo, brand, headline, description, securityPill);

        VBox card = new VBox(14);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(390);
        Label cardTitle = new Label("Dołącz do rozmowy");
        cardTitle.getStyleClass().add("card-title");
        Label cardSubtitle = new Label("Konto nie jest wymagane");
        cardSubtitle.getStyleClass().add("muted-label");

        TextField serverField = field("Adres serwera", "localhost");
        TextField roomField = field("Nazwa pokoju", "pokoj");
        String suggestedName = System.getProperty("user.name", "Uczestnik")
                .replaceAll("[^\\p{L}\\p{N}_-]", "_");
        TextField nicknameField = field("Twój nickname", suggestedName);

        HBox ports = new HBox(10);
        TextField controlPortField = field("Port TCP", "8090");
        TextField udpPortField = field("Port UDP", "8091");
        HBox.setHgrow(controlPortField, Priority.ALWAYS);
        HBox.setHgrow(udpPortField, Priority.ALWAYS);
        ports.getChildren().addAll(controlPortField, udpPortField);

        Label loginError = new Label(error == null ? "" : error);
        loginError.getStyleClass().add("form-error");
        loginError.setWrapText(true);
        loginError.setVisible(error != null && !error.isBlank());
        loginError.setManaged(loginError.isVisible());
        loginErrorLabel = loginError;

        Button connect = new Button("Dołącz do pokoju");
        connect.getStyleClass().add("primary-button");
        connect.setMaxWidth(Double.MAX_VALUE);
        ProgressBar progress = new ProgressBar();
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);

        Runnable connectAction = () -> connect(serverField, roomField, nicknameField,
                controlPortField, udpPortField, connect, progress, loginError);
        connect.setOnAction(event -> connectAction.run());
        nicknameField.setOnAction(event -> connectAction.run());

        card.getChildren().addAll(cardTitle, cardSubtitle,
                labelled("SERWER", serverField), labelled("POKÓJ", roomField),
                labelled("NICKNAME", nicknameField), labelled("PORTY", ports),
                loginError, progress, connect);

        HBox layout = new HBox(70, hero, card);
        layout.getStyleClass().add("login-layout");
        layout.setAlignment(Pos.CENTER);
        HBox.setHgrow(hero, Priority.ALWAYS);
        appRoot.getChildren().setAll(layout);
        Platform.runLater(nicknameField::requestFocus);
    }

    private void connect(TextField serverField, TextField roomField, TextField nicknameField,
                         TextField controlPortField, TextField udpPortField, Button connect,
                         ProgressBar progress, Label loginError) {
        String host = serverField.getText().trim();
        String room = roomField.getText().trim();
        String nickname = nicknameField.getText().trim();
        if (host.isBlank() || !NameRules.isValid(room) || !NameRules.isValid(nickname)) {
            showFormError(loginError,
                    "Podaj serwer oraz nazwę pokoju i nickname (1–40 liter, cyfr, _ lub -).");
            return;
        }

        int controlPort;
        int udpPort;
        try {
            controlPort = parsePort(controlPortField.getText());
            udpPort = parsePort(udpPortField.getText());
        } catch (IllegalArgumentException exception) {
            showFormError(loginError, exception.getMessage());
            return;
        }

        connectedHost = host;
        connectedRoom = room;
        connectedNickname = nickname;
        participants.clear();
        selfId = null;
        leaderId = null;
        currentEpoch = 0;
        encryptionReady = false;
        latestStatus = "Łączenie…";
        pendingError = null;
        connect.setDisable(true);
        progress.setManaged(true);
        progress.setVisible(true);
        loginError.setManaged(false);
        loginError.setVisible(false);

        long generation = connectionGeneration.incrementAndGet();
        activeGeneration = generation;
        Thread.ofVirtual().name("gui-connect").start(() -> {
            VoiceClient candidate = null;
            try {
                candidate = new VoiceClient(host, controlPort, udpPort, room, nickname,
                        new UiClientListener(generation));
                client = candidate;
                candidate.start();
                if (!candidate.isRunning() || generation != activeGeneration) {
                    candidate.close();
                    if (client == candidate) {
                        client = null;
                    }
                    if (generation == activeGeneration) {
                        activeGeneration = 0;
                        String message = pendingError == null
                                ? "Nie udało się dołączyć do pokoju." : pendingError;
                        showLoginAfterFailure(generation, message);
                    }
                    return;
                }
                Platform.runLater(() -> {
                    if (generation == activeGeneration) {
                        showRoom();
                    }
                });
            } catch (Exception exception) {
                if (candidate != null) {
                    candidate.close();
                }
                if (client == candidate) {
                    client = null;
                }
                if (generation == activeGeneration) {
                    activeGeneration = 0;
                    String message = friendlyError(exception);
                    showLoginAfterFailure(generation, message);
                }
            }
        });
    }

    private void showRoom() {
        VoiceClient active = client;
        if (active == null || !active.isRunning() || activeGeneration == 0) {
            activeGeneration = 0;
            client = null;
            if (active != null) {
                active.close();
            }
            showLogin(pendingError == null
                    ? "Połączenie z serwerem zostało zakończone." : pendingError);
            return;
        }
        BorderPane layout = new BorderPane();
        layout.getStyleClass().add("room-layout");
        layout.setLeft(createSidebar());
        layout.setTop(createHeader());
        layout.setCenter(createParticipantsPanel());
        layout.setBottom(createControlBar());
        appRoot.getChildren().setAll(layout);
        publishMemberList();
    }

    private Node createSidebar() {
        VBox sidebar = new VBox(20);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(245);

        HBox brand = new HBox(11);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label logo = new Label("CV");
        logo.getStyleClass().add("logo-small");
        Label name = new Label("CipherVoice");
        name.getStyleClass().add("brand-small");
        brand.getChildren().addAll(logo, name);

        Label channelsLabel = new Label("KANAŁY GŁOSOWE");
        channelsLabel.getStyleClass().add("section-caption");
        VBox activeRoom = new VBox(4);
        activeRoom.getStyleClass().add("active-room");
        Label room = new Label("#  " + connectedRoom);
        room.getStyleClass().add("room-name");
        Label roomMeta = new Label("Kanał szyfrowany");
        roomMeta.getStyleClass().add("room-meta");
        activeRoom.getChildren().addAll(room, roomMeta);

        Region fill = new Region();
        VBox.setVgrow(fill, Priority.ALWAYS);
        VBox profile = new VBox(5);
        profile.getStyleClass().add("profile-box");
        Label nick = new Label(connectedNickname);
        nick.getStyleClass().add("profile-name");
        Label server = new Label(connectedHost);
        server.getStyleClass().add("profile-server");
        profile.getChildren().addAll(nick, server);

        sidebar.getChildren().addAll(brand, new Separator(), channelsLabel,
                activeRoom, fill, profile);
        return sidebar;
    }

    private Node createHeader() {
        HBox header = new HBox(18);
        header.getStyleClass().add("top-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        VBox names = new VBox(3);
        Label roomTitle = new Label("# " + connectedRoom);
        roomTitle.getStyleClass().add("room-title");
        roomSubtitle = new Label("Grupowy kanał głosowy");
        roomSubtitle.getStyleClass().add("muted-label");
        names.getChildren().addAll(roomTitle, roomSubtitle);

        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        epochLabel = new Label("Epoka " + currentEpoch);
        epochLabel.getStyleClass().add("epoch-badge");
        encryptionLabel = new Label("Uzgadnianie klucza…");
        encryptionLabel.setText(encryptionReady ? "E2EE aktywne" : "Uzgadnianie klucza…");
        encryptionLabel.getStyleClass().add(
                encryptionReady ? "encryption-ready" : "encryption-waiting");
        header.getChildren().addAll(names, fill, epochLabel, encryptionLabel);
        return header;
    }

    private Node createParticipantsPanel() {
        VBox panel = new VBox(14);
        panel.getStyleClass().add("content-panel");
        Label title = new Label("Uczestnicy");
        title.getStyleClass().add("panel-title");
        Label description = new Label("Osoby aktualnie połączone z kanałem");
        description.getStyleClass().add("muted-label");

        participantList = new ListView<>(participants);
        participantList.getStyleClass().add("participant-list");
        participantList.setPlaceholder(new Label("Nikt jeszcze nie dołączył"));
        participantList.setCellFactory(ignored -> new MemberCell());
        VBox.setVgrow(participantList, Priority.ALWAYS);

        errorBanner = new Label();
        errorBanner.getStyleClass().add("error-banner");
        errorBanner.setWrapText(true);
        errorBanner.setText(pendingError == null ? "" : pendingError);
        errorBanner.setVisible(pendingError != null);
        errorBanner.setManaged(pendingError != null);
        panel.getChildren().addAll(title, description, participantList, errorBanner);
        return panel;
    }

    private Node createControlBar() {
        HBox bar = new HBox(12);
        bar.getStyleClass().add("control-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox status = new VBox(3);
        Label statusTitle = new Label("STAN POŁĄCZENIA");
        statusTitle.getStyleClass().add("section-caption");
        connectionLabel = new Label(latestStatus);
        connectionLabel.getStyleClass().add("connected-label");
        status.getChildren().addAll(statusTitle, connectionLabel);

        microphoneLevel = new ProgressBar(0);
        microphoneLevel.getStyleClass().add("microphone-level");
        microphoneLevel.setPrefWidth(120);

        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);

        muteButton = new ToggleButton("Wycisz mikrofon");
        muteButton.getStyleClass().add("control-button");
        muteButton.setOnAction(event -> {
            boolean muted = muteButton.isSelected();
            muteButton.setText(muted ? "Włącz mikrofon" : "Wycisz mikrofon");
            if (client != null) client.setMuted(muted);
        });

        deafenButton = new ToggleButton("Wycisz dźwięk");
        deafenButton.getStyleClass().add("control-button");
        deafenButton.setOnAction(event -> {
            boolean deafened = deafenButton.isSelected();
            deafenButton.setText(deafened ? "Włącz dźwięk" : "Wycisz dźwięk");
            if (client != null) client.setDeafened(deafened);
        });

        Button disconnectButton = new Button("Rozłącz");
        disconnectButton.getStyleClass().add("danger-button");
        disconnectButton.setOnAction(event -> disconnect());
        bar.getChildren().addAll(status, microphoneLevel, fill,
                muteButton, deafenButton, disconnectButton);
        return bar;
    }

    private void disconnect() {
        activeGeneration = 0;
        VoiceClient active = client;
        client = null;
        if (active != null) active.close();
        speakingUntil.clear();
        hadSpeakingParticipant = false;
        participants.clear();
        pendingError = null;
        showLogin(null);
    }

    private void clearRoomReferences() {
        participantList = null;
        connectionLabel = null;
        encryptionLabel = null;
        epochLabel = null;
        roomSubtitle = null;
        errorBanner = null;
        loginErrorLabel = null;
        microphoneLevel = null;
        muteButton = null;
        deafenButton = null;
    }

    private void startSpeakingAnimation() {
        speakingAnimation = new Timeline(new KeyFrame(Duration.millis(120), event -> {
            long now = System.nanoTime();
            speakingUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
            boolean hasSpeakingParticipant = !speakingUntil.isEmpty();
            if ((hasSpeakingParticipant || hadSpeakingParticipant) && participantList != null) {
                speakingPhase = (speakingPhase + 1) % 3;
                participantList.refresh();
            }
            hadSpeakingParticipant = hasSpeakingParticipant;
        }));
        speakingAnimation.setCycleCount(Timeline.INDEFINITE);
        speakingAnimation.play();
    }

    private void registerAudioLevel(UUID participantId, double level) {
        if (participantId != null && level >= SPEAKING_THRESHOLD) {
            speakingUntil.put(participantId,
                    System.nanoTime() + SPEAKING_HANGOVER_NANOS);
        }
    }

    private boolean isSpeaking(UUID participantId) {
        Long until = speakingUntil.get(participantId);
        return until != null && until > System.nanoTime();
    }

    private void publishMemberList() {
        if (participantList != null && roomSubtitle != null) {
            participantList.refresh();
            roomSubtitle.setText(participants.size()
                    + (participants.size() == 1 ? " uczestnik" : " uczestników"));
        }
    }

    private static VBox labelled(String title, Node input) {
        Label label = new Label(title);
        label.getStyleClass().add("field-label");
        return new VBox(6, label, input);
    }

    private static TextField field(String prompt, String value) {
        TextField field = new TextField(value);
        field.setPromptText(prompt);
        field.getStyleClass().add("app-field");
        return field;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65_535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Port musi być liczbą od 1 do 65535.");
        }
    }

    private static void showFormError(Label label, String message) {
        label.setText(message);
        label.setManaged(true);
        label.setVisible(true);
    }

    private static String friendlyError(Exception exception) {
        if (exception instanceof ConnectException) {
            return "Serwer nie odpowiada. Najpierw uruchom plik server JAR.";
        }
        if (exception instanceof SocketTimeoutException) {
            return "Przekroczono czas oczekiwania na serwer.";
        }
        if (exception instanceof UnknownHostException) {
            return "Nie można odnaleźć serwera o podanym adresie.";
        }
        if (exception instanceof NoRouteToHostException) {
            return "Brak trasy do serwera. Sprawdź sieć i zaporę.";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "Nie udało się połączyć z serwerem." : message;
    }

    private void showLoginAfterFailure(long failedGeneration, String message) {
        Platform.runLater(() -> {
            // Do not overwrite a newer connection attempt started by the user.
            if (activeGeneration == 0 && connectionGeneration.get() == failedGeneration) {
                showLogin(message);
            }
        });
    }

    @Override
    public void stop() {
        activeGeneration = 0;
        if (speakingAnimation != null) {
            speakingAnimation.stop();
        }
        VoiceClient active = client;
        client = null;
        if (active != null) {
            active.close();
        }
    }

    private final class UiClientListener implements VoiceClientListener {
        private final long generation;

        private UiClientListener(long generation) {
            this.generation = generation;
        }

        private boolean current() {
            return generation == activeGeneration;
        }

        @Override
        public void onStatus(String status) {
            if (!current()) {
                return;
            }
            latestStatus = status;
            Platform.runLater(() -> {
                if (current() && connectionLabel != null) {
                    connectionLabel.setText(status);
                }
            });
        }

        @Override
        public void onRoomState(List<MemberInfo> newMembers, UUID newSelfId,
                                UUID newLeaderId, int epoch) {
            if (!current()) {
                return;
            }
            selfId = newSelfId;
            leaderId = newLeaderId;
            currentEpoch = epoch;
            Platform.runLater(() -> {
                if (!current()) {
                    return;
                }
                participants.setAll(newMembers);
                if (epochLabel != null) {
                    epochLabel.setText("Epoka " + epoch);
                }
                publishMemberList();
            });
        }

        @Override
        public void onEncryptionChanged(boolean ready, int epoch) {
            if (!current()) {
                return;
            }
            encryptionReady = ready;
            Platform.runLater(() -> {
                if (!current() || encryptionLabel == null) {
                    return;
                }
                encryptionLabel.setText(ready ? "E2EE aktywne" : "Uzgadnianie klucza…");
                encryptionLabel.getStyleClass().removeAll(
                        "encryption-ready", "encryption-waiting");
                encryptionLabel.getStyleClass().add(
                        ready ? "encryption-ready" : "encryption-waiting");
            });
        }

        @Override
        public void onLocalAudioLevel(double level) {
            if (!current()) {
                return;
            }
            registerAudioLevel(selfId, level);
            Platform.runLater(() -> {
                if (current() && microphoneLevel != null) {
                    microphoneLevel.setProgress(level);
                }
            });
        }

        @Override
        public void onParticipantAudioLevel(UUID participantId, double level) {
            if (current()) {
                registerAudioLevel(participantId, level);
            }
        }

        @Override
        public void onError(String message) {
            if (!current()) {
                return;
            }
            pendingError = message;
            Platform.runLater(() -> {
                if (!current()) {
                    return;
                }
                if (errorBanner != null) {
                    errorBanner.setText(message);
                    errorBanner.setManaged(true);
                    errorBanner.setVisible(true);
                } else if (loginErrorLabel != null) {
                    showFormError(loginErrorLabel, message);
                }
            });
        }

        @Override
        public void onDisconnected(String reason) {
            if (!current()) {
                return;
            }
            Platform.runLater(() -> {
                if (!current()) {
                    return;
                }
                activeGeneration = 0;
                client = null;
                speakingUntil.clear();
                hadSpeakingParticipant = false;
                participants.clear();
                pendingError = reason;
                showLogin(reason);
            });
        }
    }

    private final class MemberCell extends ListCell<MemberInfo> {
        @Override
        protected void updateItem(MemberInfo member, boolean empty) {
            super.updateItem(member, empty);
            if (empty || member == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            UUID memberId = UUID.fromString(member.clientId());
            String cleanName = member.nickname() == null ? "?" : member.nickname().trim();
            String letters = cleanName.isEmpty() ? "?"
                    : cleanName.substring(0, Math.min(2, cleanName.length())).toUpperCase();
            Label initials = new Label(letters);
            initials.getStyleClass().add("avatar-text");
            StackPane avatar = new StackPane(initials);
            avatar.getStyleClass().add("avatar");
            boolean speaking = isSpeaking(memberId);
            if (speaking) avatar.getStyleClass().add("speaking-avatar");

            Label nickname = new Label(member.nickname());
            nickname.getStyleClass().add("participant-name");
            String role = memberId.equals(selfId) ? "Ty" : "Uczestnik";
            if (memberId.equals(leaderId)) role += "  •  lider klucza";
            Label details = new Label(role);
            details.getStyleClass().add("participant-details");
            VBox text = new VBox(3, nickname, details);

            Region fill = new Region();
            HBox.setHgrow(fill, Priority.ALWAYS);
            Node presence;
            if (speaking) {
                HBox dots = new HBox(3);
                dots.setAlignment(Pos.CENTER);
                dots.getStyleClass().add("speaking-dots");
                for (int i = 0; i < 3; i++) {
                    Region dot = new Region();
                    dot.getStyleClass().add("speaking-dot");
                    if (i == speakingPhase) dot.getStyleClass().add("active");
                    dots.getChildren().add(dot);
                }
                Label speakingLabel = new Label("mówi");
                speakingLabel.getStyleClass().add("speaking-label");
                HBox speakingIndicator = new HBox(7, dots, speakingLabel);
                speakingIndicator.setAlignment(Pos.CENTER);
                presence = speakingIndicator;
            } else {
                Label online = new Label("●  online");
                online.getStyleClass().add("online-label");
                presence = online;
            }
            HBox row = new HBox(13, avatar, text, fill, presence);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("participant-row");
            if (speaking) row.getStyleClass().add("speaking");
            setGraphic(row);
        }
    }
}
