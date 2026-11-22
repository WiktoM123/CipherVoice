package pl.ciphervoice.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import pl.ciphervoice.common.MemberInfo;
import pl.ciphervoice.common.NameRules;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

public class VoiceChatApplication extends Application {
    private static final double SPEAKING_THRESHOLD = 0.04;
    private static final long SPEAKING_HANGOVER_NANOS = 500_000_000L;

    private final ObservableList<MemberInfo> participants = FXCollections.observableArrayList();
    private final Map<UUID, Long> speakingUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Double> participantVolumes = new ConcurrentHashMap<>();
    private final Set<UUID> knownMemberIds = new HashSet<>();
    private final Set<MemberCell> activeCells =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final AppSettings settings = new AppSettings();
    private final SoundEffects soundEffects = new SoundEffects();
    private Stage stage;
    private StackPane appRoot;
    private final AtomicLong connectionGeneration = new AtomicLong();
    private volatile VoiceClient client;
    private volatile long activeGeneration;
    private ListView<MemberInfo> participantList;
    private Label connectionLabel;
    private Label pingLabel;
    private Label encryptionLabel;
    private Label epochLabel;
    private Label roomSubtitle;
    private Label errorBanner;
    private Label loginErrorLabel;
    private Label pttBadge;
    private ProgressBar microphoneLevel;
    private ToggleButton muteButton;
    private ToggleButton deafenButton;
    private Timeline speakingAnimation;
    private Timeline starAnimation;
    private Canvas starCanvas;
    private boolean membersInitialized;
    private boolean pttKeyDown;
    private boolean audioSettingsOpen;
    private volatile UUID selfId;
    private volatile UUID leaderId;
    private volatile int currentEpoch;
    private volatile boolean encryptionReady;
    private volatile String latestStatus = "Połączono";
    private volatile String pendingError;
    private String connectedHost;
    private String connectedRoom;
    private String connectedNickname;
    private Mixer.Info selectedMicrophone;
    private Mixer.Info selectedSpeaker;
    private double microphoneGain = 1.0;
    private double noiseGateThreshold = 0.03;
    private boolean pttEnabled;
    private KeyCode pttKey = KeyCode.V;
    private boolean systemSoundsEnabled = true;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        loadPreferences();
        appRoot = new StackPane();
        appRoot.getStyleClass().add("app-root");
        Scene scene = new Scene(appRoot, 1140, 740);
        scene.getStylesheets().add(getClass().getResource("/voice.css").toExternalForm());
        installRoomShortcuts(scene);
        stage.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused) {
                releasePushToTalk();
            }
        });
        stage.setTitle("CipherVoice — bezpieczne rozmowy grupowe");
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        var iconStream = getClass().getResourceAsStream("/icon.png");
        if (iconStream != null) {
            stage.getIcons().add(new javafx.scene.image.Image(iconStream));
        }
        stage.setScene(scene);
        initStarBackground();
        startSpeakingAnimation();
        showLogin(null);
        stage.show();
        WindowsThemeSupport.enableDarkTitleBar(stage.getTitle());
    }

    private void loadPreferences() {
        microphoneGain = settings.microphoneGain();
        noiseGateThreshold = settings.noiseGateThreshold();
        pttEnabled = settings.pttEnabled();
        pttKey = settings.pttKey();
        systemSoundsEnabled = settings.systemSoundsEnabled();
        selectedMicrophone = findMixerByName(OpusVoiceEngine.getAvailableMicrophones(), settings.microphoneName());
        selectedSpeaker = findMixerByName(OpusVoiceEngine.getAvailableSpeakers(), settings.speakerName());
    }

    private static Mixer.Info findMixerByName(List<Mixer.Info> mixers, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Mixer.Info info : mixers) {
            if (name.equals(info.getName())) {
                return info;
            }
        }
        return null;
    }

    private void installRoomShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!isRoomActive()) {
                return;
            }
            if (event.isControlDown() && event.getCode() == KeyCode.M) {
                event.consume();
                if (muteButton != null) {
                    muteButton.fire();
                }
                return;
            }
            if (event.isControlDown() && event.getCode() == KeyCode.D) {
                event.consume();
                if (deafenButton != null) {
                    deafenButton.fire();
                }
                return;
            }
            if (pttEnabled && event.getCode() == pttKey) {
                event.consume();
                if (!pttKeyDown) {
                    pttKeyDown = true;
                    updatePushToTalkState();
                }
            }
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (pttEnabled && event.getCode() == pttKey && pttKeyDown) {
                event.consume();
                pttKeyDown = false;
                updatePushToTalkState();
            }
        });
    }

    private boolean isRoomActive() {
        return client != null && activeGeneration != 0 && !audioSettingsOpen;
    }

    private void releasePushToTalk() {
        if (pttKeyDown) {
            pttKeyDown = false;
            updatePushToTalkState();
        }
    }

    private void updatePushToTalkState() {
        VoiceClient active = client;
        if (active != null) {
            active.setPushToTalk(pttEnabled, pttKeyDown);
        }
        if (pttBadge != null) {
            pttBadge.setVisible(pttEnabled);
            pttBadge.setManaged(pttEnabled);
            pttBadge.getStyleClass().remove("ptt-active");
            if (pttEnabled) {
                if (pttKeyDown) {
                    pttBadge.setText("🎙 PTT aktywne (" + keyDisplayName(pttKey) + ")");
                    pttBadge.getStyleClass().add("ptt-active");
                } else {
                    pttBadge.setText("PTT: trzymaj " + keyDisplayName(pttKey));
                }
            }
        }
    }

    private void initStarBackground() {
        if (starCanvas != null) return;
        starCanvas = new Canvas();
        starCanvas.widthProperty().bind(appRoot.widthProperty());
        starCanvas.heightProperty().bind(appRoot.heightProperty());
        starCanvas.setMouseTransparent(true);

        record Star(double x, double y, double baseOpacity, double radius, double speed) {}
        java.util.List<Star> stars = new java.util.ArrayList<>();
        var rng = new java.util.Random(42);
        for (int i = 0; i < 140; i++) {
            stars.add(new Star(
                    rng.nextDouble(), rng.nextDouble(),
                    0.08 + rng.nextDouble() * 0.28,
                    0.5 + rng.nextDouble() * 1.4,
                    0.3 + rng.nextDouble() * 0.6));
        }

        starAnimation = new Timeline(new KeyFrame(Duration.millis(80), event -> {
            GraphicsContext gc = starCanvas.getGraphicsContext2D();
            double w = starCanvas.getWidth(), h = starCanvas.getHeight();
            gc.clearRect(0, 0, w, h);
            double time = System.currentTimeMillis() / 1000.0;
            for (Star s : stars) {
                double flicker = s.baseOpacity
                        + 0.14 * Math.sin(time * s.speed * 2.5 + s.x * 10);
                flicker = Math.max(0.04, Math.min(0.65, flicker));
                gc.setFill(Color.rgb(200, 190, 255, flicker));
                gc.fillOval(s.x * w, s.y * h, s.radius * 2, s.radius * 2);
            }
        }));
        starAnimation.setCycleCount(Timeline.INDEFINITE);
        starAnimation.play();
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

        VBox card = new VBox(15);
        card.getStyleClass().add("login-card");
        card.setMaxWidth(430);
        card.setPrefWidth(430);
        Label cardTitle = new Label("Dołącz do rozmowy");
        cardTitle.getStyleClass().add("card-title");
        Label cardSubtitle = new Label("Konto nie jest wymagane");
        cardSubtitle.getStyleClass().add("muted-label");

        TextField serverField = field("Adres serwera", settings.serverHost());
        TextField roomField = field("Nazwa pokoju", settings.roomName());
        String suggestedName = System.getProperty("user.name", "Uczestnik")
                .replaceAll("[^\\p{L}\\p{N}_-]", "_");
        TextField nicknameField = field("Twój nickname", settings.nickname(suggestedName));

        HBox ports = new HBox(12);
        TextField controlPortField = field("Port TCP", String.valueOf(settings.controlPort()));
        TextField udpPortField = field("Port UDP", String.valueOf(settings.udpPort()));
        VBox controlPortBox = labelled("PORT TCP", controlPortField);
        VBox udpPortBox = labelled("PORT UDP", udpPortField);
        HBox.setHgrow(controlPortBox, Priority.ALWAYS);
        HBox.setHgrow(udpPortBox, Priority.ALWAYS);
        ports.getChildren().addAll(controlPortBox, udpPortBox);

        Label loginError = new Label(error == null ? "" : error);
        loginError.getStyleClass().add("form-error");
        loginError.setWrapText(true);
        loginError.setVisible(error != null && !error.isBlank());
        loginError.setManaged(loginError.isVisible());
        loginErrorLabel = loginError;

        Button connect = new Button("Dołącz do pokoju");
        connect.getStyleClass().add("primary-button");
        connect.setMaxWidth(Double.MAX_VALUE);

        Button audioSettings = new Button("⚙ Ustawienia audio");
        audioSettings.getStyleClass().add("secondary-button");
        audioSettings.setMaxWidth(Double.MAX_VALUE);
        audioSettings.setOnAction(event -> showAudioSettingsDialog());

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
                labelled("NICKNAME", nicknameField), ports,
                loginError, progress, connect, audioSettings);

        HBox layout = new HBox(64, hero, card);
        layout.getStyleClass().add("login-layout");
        layout.setAlignment(Pos.CENTER);
        HBox.setHgrow(hero, Priority.ALWAYS);
        appRoot.getChildren().setAll(starCanvas, layout);
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

        settings.setServerHost(host);
        settings.setRoomName(room);
        settings.setNickname(nickname);
        settings.setControlPort(controlPort);
        settings.setUdpPort(udpPort);

        connectedHost = host;
        connectedRoom = room;
        connectedNickname = nickname;
        participants.clear();
        knownMemberIds.clear();
        membersInitialized = false;
        pttKeyDown = false;
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
                        new UiClientListener(generation), selectedMicrophone, selectedSpeaker);
                candidate.setMicrophoneGain(microphoneGain);
                candidate.setNoiseGateThreshold(noiseGateThreshold);
                candidate.setPushToTalk(pttEnabled, false);
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
        layout.setTop(createHeader());
        layout.setCenter(createParticipantsPanel());
        layout.setBottom(createControlBar());
        appRoot.getChildren().setAll(starCanvas, layout);
        publishMemberList();
    }

    private Node createHeader() {
        HBox header = new HBox(18);
        header.getStyleClass().add("top-bar");
        header.setAlignment(Pos.CENTER_LEFT);

        VBox names = new VBox(3);
        Label roomTitle = new Label("# " + connectedRoom);
        roomTitle.getStyleClass().add("room-title");
        roomSubtitle = new Label("Kanał szyfrowany  •  " + connectedHost);
        roomSubtitle.getStyleClass().add("muted-label");
        names.getChildren().addAll(roomTitle, roomSubtitle);

        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);

        pingLabel = new Label("Ping: —");
        pingLabel.getStyleClass().add("ping-badge");

        epochLabel = new Label("Epoka " + currentEpoch);
        epochLabel.getStyleClass().add("epoch-badge");

        encryptionLabel = new Label(encryptionReady ? "🔒 E2EE aktywne" : "Uzgadnianie klucza…");
        encryptionLabel.getStyleClass().add(
                encryptionReady ? "encryption-ready" : "encryption-waiting");
        encryptionLabel.setTooltip(new Tooltip(
                "Kliknij, aby zobaczyć odciski kluczy i podgląd szyfrowanych pakietów"));
        encryptionLabel.setOnMouseClicked(e -> showEncryptionInspectorDialog());

        header.getChildren().addAll(names, fill, pingLabel, epochLabel, encryptionLabel);
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
        HBox bar = new HBox(14);
        bar.getStyleClass().add("control-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox status = new VBox(4);
        Label statusTitle = new Label("STAN POŁĄCZENIA");
        statusTitle.getStyleClass().add("section-caption");
        connectionLabel = new Label(latestStatus);
        connectionLabel.getStyleClass().add("connected-label");
        connectionLabel.setMinWidth(Region.USE_PREF_SIZE);
        status.getChildren().addAll(statusTitle, connectionLabel);
        status.setMinWidth(Region.USE_PREF_SIZE);

        Label micMeterCaption = new Label("POZIOM MIKROFONU");
        micMeterCaption.getStyleClass().add("section-caption");
        micMeterCaption.setMinWidth(Region.USE_PREF_SIZE);
        microphoneLevel = new ProgressBar(0);
        microphoneLevel.getStyleClass().add("microphone-level");
        microphoneLevel.setPrefWidth(130);
        microphoneLevel.setPrefHeight(10);
        VBox micMeterBox = new VBox(5, micMeterCaption, microphoneLevel);
        micMeterBox.setAlignment(Pos.CENTER_LEFT);
        micMeterBox.setMinWidth(Region.USE_PREF_SIZE);

        pttBadge = new Label();
        pttBadge.getStyleClass().add("ptt-badge");
        pttBadge.setMinWidth(Region.USE_PREF_SIZE);
        updatePushToTalkState();

        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);

        muteButton = new ToggleButton("Wycisz mikrofon");
        muteButton.getStyleClass().add("control-button");
        muteButton.setMinWidth(Region.USE_PREF_SIZE);
        muteButton.setOnAction(event -> {
            boolean muted = muteButton.isSelected();
            muteButton.setText(muted ? "Włącz mikrofon" : "Wycisz mikrofon");
            if (client != null) client.setMuted(muted);
            if (systemSoundsEnabled && (deafenButton == null || !deafenButton.isSelected())) {
                soundEffects.playAsync(muted ? SoundEffects.Cue.MUTE : SoundEffects.Cue.UNMUTE,
                        selectedSpeaker, 1.0);
            }
        });

        deafenButton = new ToggleButton("Wycisz dźwięk");
        deafenButton.getStyleClass().add("control-button");
        deafenButton.setMinWidth(Region.USE_PREF_SIZE);
        deafenButton.setOnAction(event -> {
            boolean deafened = deafenButton.isSelected();
            deafenButton.setText(deafened ? "Włącz dźwięk" : "Wycisz dźwięk");
            if (client != null) client.setDeafened(deafened);
        });

        Button settingsButton = new Button("⚙ Ustawienia");
        settingsButton.getStyleClass().add("control-button");
        settingsButton.setMinWidth(Region.USE_PREF_SIZE);
        settingsButton.setOnAction(event -> showAudioSettingsDialog());

        Button disconnectButton = new Button("Rozłącz");
        disconnectButton.getStyleClass().add("danger-button");
        disconnectButton.setMinWidth(Region.USE_PREF_SIZE);
        disconnectButton.setOnAction(event -> disconnect());
        bar.getChildren().addAll(status, micMeterBox, pttBadge, fill,
                muteButton, deafenButton, settingsButton, disconnectButton);
        return bar;
    }

    private void disconnect() {
        activeGeneration = 0;
        pttKeyDown = false;
        VoiceClient active = client;
        client = null;
        if (active != null) active.close();
        speakingUntil.clear();
        knownMemberIds.clear();
        membersInitialized = false;
        participants.clear();
        pendingError = null;
        showLogin(null);
    }

    private void clearRoomReferences() {
        activeCells.clear();
        participantList = null;
        connectionLabel = null;
        pingLabel = null;
        encryptionLabel = null;
        epochLabel = null;
        roomSubtitle = null;
        errorBanner = null;
        loginErrorLabel = null;
        pttBadge = null;
        microphoneLevel = null;
        muteButton = null;
        deafenButton = null;
    }

    private void startSpeakingAnimation() {
        speakingAnimation = new Timeline(new KeyFrame(Duration.millis(150), event -> {
            long now = System.nanoTime();
            speakingUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
            for (MemberCell cell : activeCells) {
                cell.syncSpeakingState();
            }
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
            if (roomSubtitle != null) {
                roomSubtitle.setText("Kanał szyfrowany  •  " + connectedHost + "  •  "
                        + participants.size() + (participants.size() == 1 ? " uczestnik" : " uczestników"));
            }
        }
    }

    private record MixerOption(Mixer.Info info, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record PttKeyOption(KeyCode code, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static String keyDisplayName(KeyCode code) {
        if (code == null) {
            return "V";
        }
        return switch (code) {
            case SPACE -> "Spacja";
            case CONTROL -> "Ctrl";
            case SHIFT -> "Shift";
            default -> code.getName();
        };
    }

    private void showAudioSettingsDialog() {
        audioSettingsOpen = true;
        releasePushToTalk();
        Stage dialog = new Stage();
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Ustawienia audio i skrótów — CipherVoice");

        VBox content = new VBox(16);
        content.getStyleClass().add("settings-card");
        content.setPrefWidth(820);

        Label title = new Label("⚙ Ustawienia audio i skrótów");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("Zmiana urządzeń zadziała po ponownym dołączeniu. Czułość, bramka szumów i PTT działają od razu.");
        subtitle.getStyleClass().add("muted-label");
        subtitle.setWrapText(true);

        // Microphone combo
        ComboBox<MixerOption> micCombo = new ComboBox<>();
        micCombo.setMaxWidth(Double.MAX_VALUE);
        javafx.util.Callback<ListView<MixerOption>, ListCell<MixerOption>> micCellFactory = lv -> new ListCell<>() {
            @Override
            protected void updateItem(MixerOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        };
        micCombo.setCellFactory(micCellFactory);
        micCombo.setButtonCell(micCellFactory.call(null));

        MixerOption defaultMic = new MixerOption(null, "Domyślny mikrofon systemowy");
        micCombo.getItems().add(defaultMic);
        MixerOption currentMicOpt = defaultMic;
        for (Mixer.Info info : OpusVoiceEngine.getAvailableMicrophones()) {
            MixerOption opt = new MixerOption(info, info.getName());
            micCombo.getItems().add(opt);
            if (selectedMicrophone != null && selectedMicrophone.getName().equals(info.getName())) {
                currentMicOpt = opt;
            }
        }
        micCombo.setValue(currentMicOpt);

        // Mic gain & unified noise gate + live level meter
        AtomicReference<Double> previewGain = new AtomicReference<>(microphoneGain);
        AtomicReference<Double> previewGate = new AtomicReference<>(noiseGateThreshold);

        Slider gainSlider = new Slider(0.25, 3.0, microphoneGain);
        StackPane gainControl = styledSlider(gainSlider);
        Label gainValue = new Label(Math.round(microphoneGain * 100) + "%");
        gainValue.getStyleClass().add("value-badge");
        gainSlider.valueProperty().addListener((obs, o, n) -> {
            previewGain.set(n.doubleValue());
            gainValue.setText(Math.round(n.doubleValue() * 100) + "%");
        });
        VBox gainSection = sliderSection("WZMOCNIENIE MIKROFONU (CZUŁOŚĆ)", gainControl, gainValue);

        Slider gateSlider = new Slider(0.0, 0.40, noiseGateThreshold);
        gateSlider.getStyleClass().add("gate-slider");
        gateSlider.setMaxWidth(Double.MAX_VALUE);

        ProgressBar micTestBar = new ProgressBar(0);
        micTestBar.getStyleClass().add("noise-gate-meter");
        micTestBar.setMaxWidth(Double.MAX_VALUE);
        StackPane.setMargin(micTestBar, new Insets(0, 5, 0, 5));

        StackPane gateControl = new StackPane(micTestBar, gateSlider);
        gateControl.setAlignment(Pos.CENTER);
        gateControl.setMaxWidth(Double.MAX_VALUE);

        Label gateValue = new Label(formatGateLabel(noiseGateThreshold));
        gateValue.getStyleClass().add("value-badge");
        Label gateStateBadge = new Label(noiseGateThreshold < 0.005 ? "Wyłączona" : "Test gotowy");
        gateStateBadge.getStyleClass().add("gate-status");

        Label gateHint = new Label("Podczas testu zielony pasek oznacza transmisję, a pomarańczowy szum poniżej progu.");
        gateHint.getStyleClass().add("field-hint");
        gateHint.setWrapText(true);

        AtomicBoolean testingMic = new AtomicBoolean();
        gateSlider.valueProperty().addListener((obs, o, n) -> {
            double threshold = n.doubleValue();
            previewGate.set(threshold);
            gateValue.setText(formatGateLabel(threshold));
            if (!testingMic.get()) {
                updateGatePreviewState(micTestBar, gateStateBadge, false, threshold < 0.005, false);
            }
        });

        HBox gateHeader = headerWithBadges("BRAMKA SZUMÓW (NOISE GATE)", gateStateBadge, gateValue);
        VBox gateSection = new VBox(8, gateHeader, gateControl, gateHint);

        ToggleButton micTestBtn = new ToggleButton("🎤 Rozpocznij test mikrofonu");
        micTestBtn.getStyleClass().add("control-button");
        micTestBtn.setMaxWidth(Double.MAX_VALUE);

        Label audioTestError = new Label();
        audioTestError.getStyleClass().add("form-error");
        audioTestError.setWrapText(true);
        audioTestError.setVisible(false);
        audioTestError.setManaged(false);

        micTestBtn.setOnAction(e -> {
            if (micTestBtn.isSelected()) {
                audioTestError.setVisible(false);
                audioTestError.setManaged(false);
                micTestBtn.setText("⏹ Zatrzymaj test mikrofonu");
                testingMic.set(true);
                Mixer.Info micInfo = micCombo.getValue() != null ? micCombo.getValue().info() : null;
                startMicTest(micInfo, testingMic, previewGain, previewGate, (level, gateOpen) -> {
                    double scaled = Math.max(0.0, Math.min(1.0, level / 0.40));
                    micTestBar.setProgress(scaled);
                    updateGatePreviewState(micTestBar, gateStateBadge, true,
                            previewGate.get() < 0.005, gateOpen);
                }, () -> {
                    micTestBtn.setSelected(false);
                    micTestBtn.setText("🎤 Rozpocznij test mikrofonu");
                    micTestBar.setProgress(0);
                    updateGatePreviewState(micTestBar, gateStateBadge, false,
                            previewGate.get() < 0.005, false);
                }, msg -> showFormError(audioTestError, msg));
            } else {
                micTestBtn.setText("🎤 Rozpocznij test mikrofonu");
                testingMic.set(false);
                micTestBar.setProgress(0);
                updateGatePreviewState(micTestBar, gateStateBadge, false,
                        previewGate.get() < 0.005, false);
            }
        });

        // Speaker combo
        ComboBox<MixerOption> speakerCombo = new ComboBox<>();
        speakerCombo.setMaxWidth(Double.MAX_VALUE);
        javafx.util.Callback<ListView<MixerOption>, ListCell<MixerOption>> speakerCellFactory = lv -> new ListCell<>() {
            @Override
            protected void updateItem(MixerOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        };
        speakerCombo.setCellFactory(speakerCellFactory);
        speakerCombo.setButtonCell(speakerCellFactory.call(null));

        MixerOption defaultSpeaker = new MixerOption(null, "Domyślne głośniki / słuchawki");
        speakerCombo.getItems().add(defaultSpeaker);
        MixerOption currentSpeakerOpt = defaultSpeaker;
        for (Mixer.Info info : OpusVoiceEngine.getAvailableSpeakers()) {
            MixerOption opt = new MixerOption(info, info.getName());
            speakerCombo.getItems().add(opt);
            if (selectedSpeaker != null && selectedSpeaker.getName().equals(info.getName())) {
                currentSpeakerOpt = opt;
            }
        }
        speakerCombo.setValue(currentSpeakerOpt);

        // Sound test button
        Button soundTestBtn = new Button("🔊 Odtwórz dźwięk testowy (chime)");
        soundTestBtn.getStyleClass().add("control-button");
        soundTestBtn.setMaxWidth(Double.MAX_VALUE);
        soundTestBtn.setOnAction(e -> {
            audioTestError.setVisible(false);
            audioTestError.setManaged(false);
            Mixer.Info speakerInfo = speakerCombo.getValue() != null ? speakerCombo.getValue().info() : null;
            playTestTone(speakerInfo, 1.0, msg -> showFormError(audioTestError, msg));
        });

        // Push-to-Talk & system sounds
        CheckBox pttCheck = new CheckBox("Tryb Push-to-Talk (trzymaj klawisz)");
        pttCheck.setSelected(pttEnabled);

        ComboBox<PttKeyOption> pttKeyCombo = new ComboBox<>();
        pttKeyCombo.setMaxWidth(Double.MAX_VALUE);
        List<PttKeyOption> pttOptions = List.of(
                new PttKeyOption(KeyCode.V, "V"),
                new PttKeyOption(KeyCode.SPACE, "Spacja"),
                new PttKeyOption(KeyCode.B, "B"),
                new PttKeyOption(KeyCode.F8, "F8"),
                new PttKeyOption(KeyCode.F9, "F9"),
                new PttKeyOption(KeyCode.CONTROL, "Ctrl"),
                new PttKeyOption(KeyCode.SHIFT, "Shift")
        );
        pttKeyCombo.getItems().addAll(pttOptions);
        PttKeyOption selectedPttOpt = pttOptions.stream()
                .filter(opt -> opt.code() == pttKey)
                .findFirst()
                .orElse(pttOptions.getFirst());
        pttKeyCombo.setValue(selectedPttOpt);
        pttKeyCombo.disableProperty().bind(pttCheck.selectedProperty().not());

        CheckBox soundsCheck = new CheckBox("Dźwięki systemowe (dołączenie, wyjście, mute)");
        soundsCheck.setSelected(systemSoundsEnabled);

        Label shortcutsHint = new Label(
                "Skróty w pokoju: Ctrl+M (mikrofon), Ctrl+D (dźwięk). Głośność poszczególnych osób regulujesz bezpośrednio przy ich karcie na liście.");
        shortcutsHint.getStyleClass().add("muted-label");
        shortcutsHint.setWrapText(true);

        // Buttons
        Button saveBtn = new Button("Zapisz");
        saveBtn.getStyleClass().add("primary-button");
        saveBtn.setPrefWidth(120);

        Button cancelBtn = new Button("Anuluj");
        cancelBtn.getStyleClass().add("control-button");
        cancelBtn.setPrefWidth(100);

        saveBtn.setOnAction(e -> {
            testingMic.set(false);
            selectedMicrophone = micCombo.getValue() != null ? micCombo.getValue().info() : null;
            selectedSpeaker = speakerCombo.getValue() != null ? speakerCombo.getValue().info() : null;
            microphoneGain = gainSlider.getValue();
            noiseGateThreshold = gateSlider.getValue();
            pttEnabled = pttCheck.isSelected();
            pttKey = pttKeyCombo.getValue() != null ? pttKeyCombo.getValue().code() : KeyCode.V;
            pttKeyDown = false;
            systemSoundsEnabled = soundsCheck.isSelected();

            settings.setMicrophoneName(selectedMicrophone != null ? selectedMicrophone.getName() : null);
            settings.setSpeakerName(selectedSpeaker != null ? selectedSpeaker.getName() : null);
            settings.setMicrophoneGain(microphoneGain);
            settings.setNoiseGateThreshold(noiseGateThreshold);
            settings.setPttEnabled(pttEnabled);
            settings.setPttKey(pttKey);
            settings.setSystemSoundsEnabled(systemSoundsEnabled);

            if (client != null) {
                client.setMicrophoneGain(microphoneGain);
                client.setNoiseGateThreshold(noiseGateThreshold);
            }
            updatePushToTalkState();
            dialog.close();
        });

        cancelBtn.setOnAction(e -> {
            testingMic.set(false);
            dialog.close();
        });

        dialog.setOnHidden(e -> {
            testingMic.set(false);
            audioSettingsOpen = false;
        });

        HBox actions = new HBox(12, saveBtn, cancelBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox micBox = new VBox(14,
                labelled("URZĄDZENIE WEJŚCIOWE (MIKROFON)", micCombo),
                gainSection,
                gateSection,
                micTestBtn);
        micBox.getStyleClass().add("settings-section");

        VBox rightBox = new VBox(12,
                labelled("URZĄDZENIE WYJŚCIOWE (SŁUCHAWKI / GŁOŚNIKI)", speakerCombo),
                soundTestBtn,
                new Separator(),
                pttCheck,
                labelled("KLAWISZ PUSH-TO-TALK", pttKeyCombo),
                soundsCheck,
                shortcutsHint);
        rightBox.getStyleClass().add("settings-section");

        HBox columns = new HBox(16, micBox, rightBox);
        HBox.setHgrow(micBox, Priority.ALWAYS);
        HBox.setHgrow(rightBox, Priority.ALWAYS);
        micBox.setPrefWidth(370);
        rightBox.setPrefWidth(370);

        content.getChildren().addAll(
                new VBox(4, title, subtitle),
                columns,
                audioTestError,
                actions);

        Scene scene = new Scene(content);
        scene.setFill(Color.web("#131832"));
        scene.getStylesheets().add(getClass().getResource("/voice.css").toExternalForm());
        var dialogIconStream = getClass().getResourceAsStream("/icon.png");
        if (dialogIconStream != null) {
            dialog.getIcons().add(new javafx.scene.image.Image(dialogIconStream));
        }
        dialog.setScene(scene);
        dialog.setResizable(false);
        WindowsThemeSupport.enableDarkTitleBar(dialog.getTitle());
        dialog.showAndWait();
    }

    private void showEncryptionInspectorDialog() {
        VoiceClient activeClient = client;
        if (activeClient == null) {
            return;
        }
        Stage dialog = new Stage();
        dialog.initOwner(stage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Inspektor szyfrowania E2EE — CipherVoice");

        VBox content = new VBox(14);
        content.getStyleClass().add("settings-card");
        content.setPrefWidth(680);

        Label title = new Label("🔐 Inspektor szyfrowania E2EE (na żywo)");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label(
                "Serwer nie otrzymuje klucza grupowego. Odcisk klucza AES-256 powinien być identyczny w obu oknach klienta i zmienia się przy zmianie epoki.");
        subtitle.getStyleClass().add("muted-label");
        subtitle.setWrapText(true);

        Label metaLabel = new Label();
        metaLabel.getStyleClass().add("muted-label");

        Label identityValue = new Label();
        identityValue.getStyleClass().add("mono-readout");
        identityValue.setMaxWidth(Double.MAX_VALUE);

        Label groupKeyValue = new Label();
        groupKeyValue.getStyleClass().add("mono-readout");
        groupKeyValue.setMaxWidth(Double.MAX_VALUE);

        VBox keysBox = new VBox(10,
                metaLabel,
                labelled("TWÓJ KLUCZ PUBLICZNY X25519 (ODCISK SHA-256)", identityValue),
                labelled("GRUPOWY KLUCZ SESJI AES-256 (ODCISK SHA-256)", groupKeyValue));
        keysBox.getStyleClass().add("settings-section");

        Label packetStats = new Label();
        packetStats.getStyleClass().add("muted-label");

        Label outgoingPreview = new Label();
        outgoingPreview.getStyleClass().add("mono-readout");
        outgoingPreview.setMaxWidth(Double.MAX_VALUE);
        outgoingPreview.setWrapText(true);

        Label incomingPreview = new Label();
        incomingPreview.getStyleClass().add("mono-readout");
        incomingPreview.setMaxWidth(Double.MAX_VALUE);
        incomingPreview.setWrapText(true);

        VBox packetsBox = new VBox(10,
                packetStats,
                labelled("OSTATNI WYSŁANY PAKIET UDP (NAGŁÓWEK VOIP + AES-GCM)", outgoingPreview),
                labelled("OSTATNI ODEBRANY PAKIET UDP OD ROZMÓWCY", incomingPreview));
        packetsBox.getStyleClass().add("settings-section");

        VoiceClient.EncryptionSnapshot initial = activeClient.encryptionSnapshot();
        String wiresharkFilter = "udp.port == " + initial.udpPort()
                + " || tcp.port == " + initial.controlPort();
        Label filterPreview = new Label(wiresharkFilter);
        filterPreview.getStyleClass().add("mono-readout");
        HBox.setHgrow(filterPreview, Priority.ALWAYS);
        filterPreview.setMaxWidth(Double.MAX_VALUE);

        Button copyFilterBtn = new Button("Kopiuj filtr Wireshark");
        copyFilterBtn.getStyleClass().add("control-button");
        copyFilterBtn.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(wiresharkFilter);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            copyFilterBtn.setText("Skopiowano!");
        });

        HBox wiresharkRow = new HBox(10, filterPreview, copyFilterBtn);
        wiresharkRow.setAlignment(Pos.CENTER_LEFT);
        VBox wiresharkBox = labelled("FILTR WIRESHARK (ADAPTER: NPCAP LOOPBACK)", wiresharkRow);

        Runnable refresh = () -> {
            VoiceClient current = client;
            if (current == null) {
                return;
            }
            VoiceClient.EncryptionSnapshot snap = current.encryptionSnapshot();
            metaLabel.setText(String.format(
                    "Stan: %s   •   Epoka: %d   •   Algorytmy: X25519 + HKDF-SHA-256 + AES-256-GCM",
                    snap.active() ? "aktywne" : "uzgadnianie…", snap.epoch()));
            identityValue.setText(snap.identityFingerprint());
            groupKeyValue.setText(snap.active()
                    ? snap.groupKeyFingerprint() + "   (Epoka " + snap.epoch() + ")"
                    : "Oczekiwanie na kopertę klucza od lidera…");
            packetStats.setText(String.format(
                    "Zaszyfrowano wysłanych ramek: %d   •   Odszyfrowano odebranych ramek: %d",
                    snap.encryptedPackets(), snap.decryptedPackets()));
            outgoingPreview.setText(formatPacketPreview(snap.lastOutgoing(), "Brak wysłanego audio (powiedz coś do mikrofonu)"));
            incomingPreview.setText(formatPacketPreview(snap.lastIncoming(), "Brak odebranego audio od innego uczestnika"));
        };
        refresh.run();

        Timeline refresher = new Timeline(new KeyFrame(Duration.millis(250), e -> refresh.run()));
        refresher.setCycleCount(Timeline.INDEFINITE);
        refresher.play();

        Button closeBtn = new Button("Zamknij");
        closeBtn.getStyleClass().add("primary-button");
        closeBtn.setPrefWidth(110);
        closeBtn.setOnAction(e -> dialog.close());
        HBox actions = new HBox(closeBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        dialog.setOnHidden(e -> refresher.stop());

        content.getChildren().addAll(
                new VBox(4, title, subtitle),
                keysBox,
                packetsBox,
                wiresharkBox,
                actions);

        Scene scene = new Scene(content);
        scene.setFill(Color.web("#131832"));
        scene.getStylesheets().add(getClass().getResource("/voice.css").toExternalForm());
        var dialogIconStream = getClass().getResourceAsStream("/icon.png");
        if (dialogIconStream != null) {
            dialog.getIcons().add(new javafx.scene.image.Image(dialogIconStream));
        }
        dialog.setScene(scene);
        dialog.setResizable(false);
        WindowsThemeSupport.enableDarkTitleBar(dialog.getTitle());
        dialog.showAndWait();
    }

    private static String formatPacketPreview(VoiceClient.PacketPreview preview, String fallback) {
        if (preview == null) {
            return fallback;
        }
        return "Rozmiar: " + preview.totalBytes() + " B (epoka " + preview.epoch() + ")\n"
                + "Nonce (12 B):     " + preview.nonceHex() + "\n"
                + "Szyfrogram (hex): " + preview.ciphertextHex();
    }

    private static VBox sliderSection(String title, Node control, Label... badges) {
        return new VBox(8, headerWithBadges(title, badges), control);
    }

    private static HBox headerWithBadges(String title, Label... badges) {
        Label label = new Label(title);
        label.getStyleClass().add("field-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, label, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(badges);
        return header;
    }

    private static void updateGatePreviewState(ProgressBar meter, Label statusBadge,
                                               boolean testing, boolean gateDisabled, boolean gateOpen) {
        meter.getStyleClass().removeAll("gate-closed");
        statusBadge.getStyleClass().removeAll("gate-open", "gate-closed");
        if (!testing) {
            statusBadge.setText(gateDisabled ? "Wyłączona" : "Test gotowy");
            return;
        }
        if (gateDisabled || gateOpen) {
            statusBadge.getStyleClass().add("gate-open");
            statusBadge.setText("● Nadaje");
        } else {
            meter.getStyleClass().add("gate-closed");
            statusBadge.getStyleClass().add("gate-closed");
            statusBadge.setText("● Wyciszona");
        }
    }

    private static StackPane styledSlider(Slider slider) {
        slider.setMaxWidth(Double.MAX_VALUE);
        ProgressBar fillBar = new ProgressBar();
        fillBar.getStyleClass().add("slider-fill");
        fillBar.setMouseTransparent(true);
        fillBar.setMaxWidth(Double.MAX_VALUE);
        fillBar.progressProperty().bind(Bindings.createDoubleBinding(() -> {
            double min = slider.getMin();
            double max = slider.getMax();
            if (max <= min) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, (slider.getValue() - min) / (max - min)));
        }, slider.valueProperty(), slider.minProperty(), slider.maxProperty()));
        StackPane wrapper = new StackPane(fillBar, slider);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setMaxWidth(Double.MAX_VALUE);
        return wrapper;
    }

    private static String formatGateLabel(double threshold) {
        if (threshold < 0.005) {
            return "Wył.";
        }
        return Math.round(threshold * 100) + "%";
    }

    private void playTestTone(Mixer.Info speakerInfo, double volume,
                              java.util.function.Consumer<String> onError) {
        Thread.ofVirtual().name("audio-test-tone").start(() -> {
            try {
                AudioFormat format = OpusVoiceEngine.getFormat();
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                SourceDataLine line = (speakerInfo != null)
                        ? (SourceDataLine) AudioSystem.getMixer(speakerInfo).getLine(info)
                        : (SourceDataLine) AudioSystem.getLine(info);
                line.open(format, 960 * 4);
                line.start();
                int durationSamples = (int) (OpusVoiceEngine.SAMPLE_RATE * 0.35);
                byte[] buffer = new byte[durationSamples * 2];
                double amp = Math.min(24_000.0, 12_000.0 * Math.max(0.0, Math.min(2.0, volume)));
                for (int i = 0; i < durationSamples; i++) {
                    double angle = 2.0 * Math.PI * i * 587.33 / OpusVoiceEngine.SAMPLE_RATE;
                    double envelope = Math.sin(Math.PI * i / durationSamples);
                    short sample = (short) (Math.sin(angle) * envelope * amp);
                    buffer[2 * i] = (byte) (sample & 0xFF);
                    buffer[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
                }
                line.write(buffer, 0, buffer.length);
                line.drain();
                line.close();
            } catch (Exception exception) {
                Platform.runLater(() -> onError.accept(
                        "Nie można otworzyć wybranego wyjścia audio: " + exception.getMessage()));
            }
        });
    }

    private void startMicTest(Mixer.Info micInfo, AtomicBoolean testing,
                              AtomicReference<Double> previewGain, AtomicReference<Double> previewGate,
                              java.util.function.BiConsumer<Double, Boolean> onSample,
                              Runnable onStopped, java.util.function.Consumer<String> onError) {
        Thread.ofVirtual().name("mic-test").start(() -> {
            TargetDataLine line = null;
            try {
                AudioFormat format = OpusVoiceEngine.getFormat();
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                line = (micInfo != null)
                        ? (TargetDataLine) AudioSystem.getMixer(micInfo).getLine(info)
                        : (TargetDataLine) AudioSystem.getLine(info);
                line.open(format, 960 * 4);
                line.start();
                byte[] buffer = new byte[960 * 2];
                OpusVoiceEngine.NoiseGate gate =
                        new OpusVoiceEngine.NoiseGate(OpusVoiceEngine.NOISE_GATE_HANGOVER_FRAMES);
                while (testing.get()) {
                    int read = line.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        int samples = read / 2;
                        short[] pcm = new short[samples];
                        for (int i = 0; i < samples; i++) {
                            pcm[i] = (short) ((buffer[2 * i] & 0xFF) | (buffer[2 * i + 1] << 8));
                        }
                        OpusVoiceEngine.applyGain(pcm, samples, previewGain.get());
                        double level = OpusVoiceEngine.audioLevel(pcm, samples);
                        boolean open = gate.accept(level, previewGate.get());
                        Platform.runLater(() -> {
                            if (testing.get()) {
                                onSample.accept(level, open);
                            }
                        });
                    }
                }
            } catch (Exception exception) {
                testing.set(false);
                Platform.runLater(() -> {
                    onStopped.run();
                    onError.accept("Nie można otworzyć mikrofonu: " + exception.getMessage());
                });
            } finally {
                if (line != null) {
                    try {
                        line.stop();
                        line.close();
                    } catch (Exception ignored) {
                    }
                }
                Platform.runLater(onStopped);
            }
        });
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
        if (starAnimation != null) starAnimation.stop();
        activeGeneration = 0;
        if (speakingAnimation != null) {
            speakingAnimation.stop();
        }
        soundEffects.shutdown();
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
                Set<UUID> incomingIds = new HashSet<>();
                for (MemberInfo m : newMembers) {
                    incomingIds.add(UUID.fromString(m.clientId()));
                }
                if (membersInitialized) {
                    boolean joined = false;
                    boolean left = false;
                    for (UUID id : incomingIds) {
                        if (!id.equals(newSelfId) && !knownMemberIds.contains(id)) {
                            joined = true;
                        }
                    }
                    for (UUID id : knownMemberIds) {
                        if (!id.equals(newSelfId) && !incomingIds.contains(id)) {
                            left = true;
                        }
                    }
                    boolean deaf = deafenButton != null && deafenButton.isSelected();
                    if (systemSoundsEnabled && !deaf) {
                        if (joined) {
                            soundEffects.playAsync(SoundEffects.Cue.JOIN, selectedSpeaker, 1.0);
                        } else if (left) {
                            soundEffects.playAsync(SoundEffects.Cue.LEAVE, selectedSpeaker, 1.0);
                        }
                    }
                }
                knownMemberIds.clear();
                knownMemberIds.addAll(incomingIds);
                membersInitialized = true;

                participants.setAll(newMembers);
                if (epochLabel != null) {
                    epochLabel.setText("Epoka " + epoch);
                }
                publishMemberList();
            });
        }

        @Override
        public void onPingUpdated(long rttMs) {
            if (!current()) {
                return;
            }
            Platform.runLater(() -> {
                if (!current() || pingLabel == null) {
                    return;
                }
                pingLabel.setText("Ping: " + rttMs + " ms");
                pingLabel.getStyleClass().removeAll("ping-good", "ping-warn", "ping-bad");
                if (rttMs <= 60) {
                    pingLabel.getStyleClass().add("ping-good");
                } else if (rttMs <= 140) {
                    pingLabel.getStyleClass().add("ping-warn");
                } else {
                    pingLabel.getStyleClass().add("ping-bad");
                }
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
                encryptionLabel.setText(ready ? "🔒 E2EE aktywne" : "Uzgadnianie klucza…");
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
                pttKeyDown = false;
                client = null;
                speakingUntil.clear();
                knownMemberIds.clear();
                membersInitialized = false;
                participants.clear();
                pendingError = reason;
                showLogin(reason);
            });
        }
    }

    private final class MemberCell extends ListCell<MemberInfo> {
        private final Label initials = new Label();
        private final StackPane avatar = new StackPane(initials);
        private final Label nickname = new Label();
        private final Label details = new Label();
        private final VBox text = new VBox(3, nickname, details);
        private final Region fill = new Region();
        private final Button mutePeerBtn = new Button("🔊");
        private final Slider volumeSlider = new Slider(0.0, 2.0, 1.0);
        private final StackPane volumeControl = styledSlider(volumeSlider);
        private final Label volumeLabel = new Label("100%");
        private final HBox volumeBox = new HBox(8, mutePeerBtn, volumeControl, volumeLabel);
        private final Label speakingLabel = new Label("●  mówi");
        private final Label onlineLabel = new Label("●  online");
        private final StackPane presenceSlot = new StackPane(onlineLabel, speakingLabel);
        private final HBox row = new HBox(14, avatar, text, fill, volumeBox, presenceSlot);

        private UUID boundMemberId;
        private double lastNonZeroVolume = 1.0;
        private boolean updatingSlider;
        private boolean currentSpeaking;

        private MemberCell() {
            activeCells.add(this);
            initials.getStyleClass().add("avatar-text");
            avatar.getStyleClass().add("avatar");
            nickname.getStyleClass().add("participant-name");
            details.getStyleClass().add("participant-details");
            HBox.setHgrow(fill, Priority.ALWAYS);

            mutePeerBtn.getStyleClass().add("icon-mute-btn");
            mutePeerBtn.setTooltip(new Tooltip("Wycisz / przywróć tego uczestnika"));
            mutePeerBtn.setOnAction(e -> {
                if (boundMemberId == null) {
                    return;
                }
                if (volumeSlider.getValue() > 0.01) {
                    lastNonZeroVolume = volumeSlider.getValue();
                    volumeSlider.setValue(0.0);
                } else {
                    volumeSlider.setValue(lastNonZeroVolume > 0.05 ? lastNonZeroVolume : 1.0);
                }
            });

            volumeControl.setPrefWidth(135);
            volumeControl.setMaxWidth(135);
            volumeSlider.setPrefWidth(135);
            volumeSlider.setTooltip(new Tooltip("Zgłośnij lub ścisz tego uczestnika (0%–200%)"));
            volumeLabel.getStyleClass().add("participant-volume-label");
            volumeBox.getStyleClass().add("participant-volume-pill");
            volumeBox.setAlignment(Pos.CENTER_LEFT);
            volumeSlider.valueProperty().addListener((obs, o, n) -> {
                double vol = n.doubleValue();
                if (vol > 0.01) {
                    lastNonZeroVolume = vol;
                }
                mutePeerBtn.setText(vol <= 0.01 ? "🔇" : "🔊");
                volumeLabel.setText(Math.round(vol * 100) + "%");
                if (updatingSlider || boundMemberId == null) {
                    return;
                }
                participantVolumes.put(boundMemberId, vol);
                if (client != null) {
                    client.setParticipantVolume(boundMemberId, vol);
                }
            });

            speakingLabel.getStyleClass().add("speaking-label");
            speakingLabel.setVisible(false);
            onlineLabel.getStyleClass().add("online-label");
            StackPane.setAlignment(speakingLabel, Pos.CENTER_RIGHT);
            StackPane.setAlignment(onlineLabel, Pos.CENTER_RIGHT);
            presenceSlot.setAlignment(Pos.CENTER_RIGHT);
            presenceSlot.setMinWidth(82);
            presenceSlot.setPrefWidth(82);
            presenceSlot.setMaxWidth(82);

            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("participant-row");
        }

        private void syncSpeakingState() {
            if (boundMemberId != null) {
                applySpeakingState(isSpeaking(boundMemberId), false);
            }
        }

        private void applySpeakingState(boolean speaking, boolean force) {
            if (!force && speaking == currentSpeaking) {
                return;
            }
            currentSpeaking = speaking;
            if (speaking) {
                if (!avatar.getStyleClass().contains("speaking-avatar")) {
                    avatar.getStyleClass().add("speaking-avatar");
                }
                if (!row.getStyleClass().contains("speaking")) {
                    row.getStyleClass().add("speaking");
                }
            } else {
                avatar.getStyleClass().remove("speaking-avatar");
                row.getStyleClass().remove("speaking");
            }
            speakingLabel.setVisible(speaking);
            onlineLabel.setVisible(!speaking);
        }

        @Override
        protected void updateItem(MemberInfo member, boolean empty) {
            super.updateItem(member, empty);
            if (empty || member == null) {
                boundMemberId = null;
                applySpeakingState(false, true);
                setGraphic(null);
                setText(null);
                return;
            }
            UUID memberId = UUID.fromString(member.clientId());
            boolean memberChanged = !memberId.equals(boundMemberId);
            boundMemberId = memberId;

            String cleanName = member.nickname() == null ? "?" : member.nickname().trim();
            String letters = cleanName.isEmpty() ? "?"
                    : cleanName.substring(0, Math.min(2, cleanName.length())).toUpperCase();
            initials.setText(letters);
            nickname.setText(member.nickname());

            boolean isSelf = memberId.equals(selfId);
            String role = isSelf ? "Ty" : "Uczestnik";
            if (memberId.equals(leaderId)) role += "  •  lider klucza";
            details.setText(role);

            volumeBox.setVisible(!isSelf);
            volumeBox.setManaged(!isSelf);
            if (!isSelf && memberChanged) {
                double vol = participantVolumes.getOrDefault(memberId, 1.0);
                updatingSlider = true;
                volumeSlider.setValue(vol);
                volumeLabel.setText(Math.round(vol * 100) + "%");
                updatingSlider = false;
            }

            applySpeakingState(isSpeaking(memberId), memberChanged);
            setGraphic(row);
        }
    }
}
