package pl.ciphervoice.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
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
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import pl.ciphervoice.common.MemberInfo;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class VoiceChatApplication extends Application {
    private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,40}");
    private static final double SPEAKING_THRESHOLD = 0.04;
    private static final long SPEAKING_HANGOVER_NANOS = 420_000_000L;

    private final ObservableList<MemberInfo> participants = FXCollections.observableArrayList();
    private final Map<UUID, Long> speakingUntil = new ConcurrentHashMap<>();
    private Stage stage;
    private StackPane appRoot;
    private VoiceClient client;
    private ListView<MemberInfo> participantList;
    private Label connectionLabel;
    private Label encryptionLabel;
    private Label epochLabel;
    private Label roomSubtitle;
    private Label errorBanner;
    private ProgressBar microphoneLevel;
    private ToggleButton muteButton;
    private ToggleButton deafenButton;
    private Timeline speakingAnimation;
    private Timeline starAnimation;
    private Canvas starCanvas;
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
    private Mixer.Info selectedMicrophone;
    private Mixer.Info selectedSpeaker;

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
                    0.15 + rng.nextDouble() * 0.55,
                    0.5 + rng.nextDouble() * 1.5,
                    0.3 + rng.nextDouble() * 0.7));
        }

        starAnimation = new Timeline(new KeyFrame(Duration.millis(80), event -> {
            GraphicsContext gc = starCanvas.getGraphicsContext2D();
            double w = starCanvas.getWidth(), h = starCanvas.getHeight();
            gc.clearRect(0, 0, w, h);
            double time = System.currentTimeMillis() / 1000.0;
            for (Star s : stars) {
                double flicker = s.baseOpacity
                        + 0.25 * Math.sin(time * s.speed * 2.5 + s.x * 10);
                flicker = Math.max(0.05, Math.min(1.0, flicker));
                gc.setFill(Color.rgb(200, 190, 255, flicker));
                gc.fillOval(s.x * w, s.y * h, s.radius * 2, s.radius * 2);
            }
        }));
        starAnimation.setCycleCount(Timeline.INDEFINITE);
        starAnimation.play();
    }

    private void showLogin(String error) {
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
                labelled("NICKNAME", nicknameField), labelled("PORTY", ports),
                loginError, progress, connect, audioSettings);

        HBox layout = new HBox(70, hero, card);
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
        if (host.isBlank() || !SAFE_NAME.matcher(room).matches()
                || !SAFE_NAME.matcher(nickname).matches()) {
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

        Thread.ofVirtual().name("gui-connect").start(() -> {
            VoiceClient candidate = null;
            try {
                candidate = new VoiceClient(host, controlPort, udpPort, room, nickname,
                        new UiClientListener(), selectedMicrophone, selectedSpeaker);
                client = candidate;
                candidate.start();
                if (candidate.isRunning()) {
                    Platform.runLater(this::showRoom);
                }
            } catch (Exception exception) {
                if (candidate != null) {
                    candidate.close();
                }
                client = null;
                String message = friendlyError(exception);
                Platform.runLater(() -> showLogin(message));
            }
        });
    }

    private void showRoom() {
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

        HBox userBadge = new HBox(6, new Label("●"), new Label(connectedNickname + " (Ty)"));
        userBadge.getStyleClass().add("user-badge");
        userBadge.setAlignment(Pos.CENTER);

        epochLabel = new Label("Epoka " + currentEpoch);
        epochLabel.getStyleClass().add("epoch-badge");

        encryptionLabel = new Label(encryptionReady ? "🔒 E2EE aktywne" : "Uzgadnianie klucza…");
        encryptionLabel.getStyleClass().add(
                encryptionReady ? "encryption-ready" : "encryption-waiting");

        header.getChildren().addAll(names, fill, userBadge, epochLabel, encryptionLabel);
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
            if (deafened && !muteButton.isSelected()) muteButton.fire();
        });

        Button settingsButton = new Button("⚙ Ustawienia");
        settingsButton.getStyleClass().add("control-button");
        settingsButton.setOnAction(event -> showAudioSettingsDialog());

        Button disconnectButton = new Button("Rozłącz");
        disconnectButton.getStyleClass().add("danger-button");
        disconnectButton.setOnAction(event -> disconnect());
        bar.getChildren().addAll(status, microphoneLevel, fill,
                muteButton, deafenButton, settingsButton, disconnectButton);
        return bar;
    }

    private void disconnect() {
        VoiceClient active = client;
        client = null;
        if (active != null) active.close();
        speakingUntil.clear();
        hadSpeakingParticipant = false;
        participants.clear();
        showLogin(null);
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
        if (participantList != null) {
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

    private void showAudioSettingsDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Ustawienia audio — CipherVoice");

        VBox root = new VBox(18);
        root.getStyleClass().add("settings-card");
        root.setPrefWidth(480);

        Label title = new Label("⚙ Ustawienia urządzeń audio");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("Wybierz mikrofon oraz słuchawki lub głośniki");
        subtitle.getStyleClass().add("muted-label");

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

        // Mic live test
        ProgressBar micTestBar = new ProgressBar(0);
        micTestBar.getStyleClass().add("microphone-level");
        micTestBar.setMaxWidth(Double.MAX_VALUE);
        micTestBar.setPrefHeight(10);
        ToggleButton micTestBtn = new ToggleButton("🎤 Rozpocznij test mikrofonu");
        micTestBtn.getStyleClass().add("control-button");
        micTestBtn.setMaxWidth(Double.MAX_VALUE);

        AtomicBoolean testingMic = new AtomicBoolean();
        micTestBtn.setOnAction(e -> {
            if (micTestBtn.isSelected()) {
                micTestBtn.setText("⏹ Zatrzymaj test mikrofonu");
                testingMic.set(true);
                startMicTest(micCombo.getValue().info(), testingMic, micTestBar);
            } else {
                micTestBtn.setText("🎤 Rozpocznij test mikrofonu");
                testingMic.set(false);
                micTestBar.setProgress(0);
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
        soundTestBtn.setOnAction(e -> playTestTone(speakerCombo.getValue().info()));

        // Buttons
        Button saveBtn = new Button("Zapisz");
        saveBtn.getStyleClass().add("primary-button");
        saveBtn.setPrefWidth(120);

        Button cancelBtn = new Button("Anuluj");
        cancelBtn.getStyleClass().add("control-button");
        cancelBtn.setPrefWidth(100);

        saveBtn.setOnAction(e -> {
            testingMic.set(false);
            selectedMicrophone = micCombo.getValue().info();
            selectedSpeaker = speakerCombo.getValue().info();
            dialog.close();
        });

        cancelBtn.setOnAction(e -> {
            testingMic.set(false);
            dialog.close();
        });

        dialog.setOnCloseRequest(e -> testingMic.set(false));

        HBox actions = new HBox(12, saveBtn, cancelBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox micBox = new VBox(8, labelled("URZĄDZENIE WEJŚCIOWE (MIKROFON)", micCombo), micTestBtn, micTestBar);
        VBox speakerBox = new VBox(8, labelled("URZĄDZENIE WYJŚCIOWE (SŁUCHAWKI / GŁOŚNIKI)", speakerCombo), soundTestBtn);

        root.getChildren().addAll(new VBox(4, title, subtitle), micBox, speakerBox, new Separator(), actions);

        Scene scene = new Scene(root);
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

    private void playTestTone(Mixer.Info speakerInfo) {
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
                for (int i = 0; i < durationSamples; i++) {
                    double angle = 2.0 * Math.PI * i * 587.33 / OpusVoiceEngine.SAMPLE_RATE;
                    double envelope = Math.sin(Math.PI * i / durationSamples);
                    short sample = (short) (Math.sin(angle) * envelope * 12000);
                    buffer[2 * i] = (byte) (sample & 0xFF);
                    buffer[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
                }
                line.write(buffer, 0, buffer.length);
                line.drain();
                line.close();
            } catch (Exception ignored) {
            }
        });
    }

    private void startMicTest(Mixer.Info micInfo, AtomicBoolean testing, ProgressBar progressBar) {
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
                while (testing.get()) {
                    int read = line.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        double sum = 0;
                        int samples = read / 2;
                        for (int i = 0; i < samples; i++) {
                            short sample = (short) ((buffer[2 * i] & 0xFF) | (buffer[2 * i + 1] << 8));
                            sum += sample * sample;
                        }
                        double rms = Math.sqrt(sum / samples) / 32768.0;
                        double level = Math.min(1.0, rms * 5.0);
                        Platform.runLater(() -> progressBar.setProgress(level));
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (line != null) {
                    try {
                        line.stop();
                        line.close();
                    } catch (Exception ignored) {
                    }
                }
                Platform.runLater(() -> progressBar.setProgress(0));
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
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "Nie udało się połączyć z serwerem.";
        if (message.contains("Connection refused")) {
            return "Serwer nie odpowiada. Najpierw uruchom plik server JAR.";
        }
        return message;
    }

    @Override
    public void stop() {
        if (speakingAnimation != null) speakingAnimation.stop();
        if (starAnimation != null) starAnimation.stop();
        if (client != null) client.close();
    }

    private final class UiClientListener implements VoiceClientListener {
        @Override
        public void onStatus(String status) {
            latestStatus = status;
            Platform.runLater(() -> {
                if (connectionLabel != null) connectionLabel.setText(status);
            });
        }

        @Override
        public void onRoomState(List<MemberInfo> newMembers, UUID newSelfId,
                                UUID newLeaderId, int epoch) {
            selfId = newSelfId;
            leaderId = newLeaderId;
            currentEpoch = epoch;
            Platform.runLater(() -> {
                participants.setAll(newMembers);
                if (epochLabel != null) epochLabel.setText("Epoka " + epoch);
                publishMemberList();
            });
        }

        @Override
        public void onEncryptionChanged(boolean ready, int epoch) {
            encryptionReady = ready;
            Platform.runLater(() -> {
                if (encryptionLabel == null) return;
                encryptionLabel.setText(ready ? "E2EE aktywne" : "Uzgadnianie klucza…");
                encryptionLabel.getStyleClass().removeAll(
                        "encryption-ready", "encryption-waiting");
                encryptionLabel.getStyleClass().add(
                        ready ? "encryption-ready" : "encryption-waiting");
            });
        }

        @Override
        public void onLocalAudioLevel(double level) {
            registerAudioLevel(selfId, level);
            Platform.runLater(() -> {
                if (microphoneLevel != null) microphoneLevel.setProgress(level);
            });
        }

        @Override
        public void onParticipantAudioLevel(UUID participantId, double level) {
            registerAudioLevel(participantId, level);
        }

        @Override
        public void onError(String message) {
            pendingError = message;
            Platform.runLater(() -> {
                if (errorBanner != null) {
                    errorBanner.setText(message);
                    errorBanner.setManaged(true);
                    errorBanner.setVisible(true);
                }
            });
        }

        @Override
        public void onDisconnected(String reason) {
            Platform.runLater(() -> {
                if (connectionLabel != null) {
                    connectionLabel.setText(reason);
                    connectionLabel.getStyleClass().remove("connected-label");
                    connectionLabel.getStyleClass().add("disconnected-label");
                }
                if (muteButton != null) muteButton.setDisable(true);
                if (deafenButton != null) deafenButton.setDisable(true);
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
