package me.amir;

import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class OnyxLauncher extends Application {

    private Settings       settings;
    private ProfileManager pm;
    private Profile        activeProfile;

    private StackPane  rootStack;

    private final StackPane slotMain           = slot();
    private final StackPane slotSettings       = slot();
    private final StackPane slotInstall        = slot();
    private final StackPane slotProfileList    = slot();
    private final StackPane slotProfileEdit    = slot();
    private final StackPane slotContentManager  = slot();
    private final StackPane slotContentBrowser  = slot();
    private final StackPane slotOverlay        = slot();
    private final StackPane slotLog            = slot();

    private AnchorPane popupLayer;
    private VBox       accountPopup;
    private VBox       profilePopup;

    private Button profileBtn;
    private Button usernameBtn;
    // ── hero banner labels (updated when active profile changes) ─────────────
    private Label  heroBadgeLbl;
    private Label  heroNameLbl;
    private Label  heroVerLbl;

    private TabPane          installTabPane;
    private ComboBox<String> installMcCombo;
    private ComboBox<String> installLoaderCombo;
    private ProgressBar      installBar;
    private Label            installStatus;
    private Button           installStartBtn;

    private TextArea           debugArea;
    private final StringBuilder logBuf = new StringBuilder();

    private TrayIcon trayIcon;
    private boolean  isGameRunning = false;



    @Override
    public void start(Stage stage) {
        settings      = Settings.load();
        pm            = ProfileManager.get();
        activeProfile = pm.getById(settings.getActiveProfileId());

        try { stage.getIcons().add(new Image(getClass().getResourceAsStream("/icon.png"))); }
        catch (Exception ignored) {}
        Platform.setImplicitExit(false);

        loadFont("/fonts/Ubuntu-Regular.ttf");
        loadFont("/fonts/Ubuntu-Medium.ttf");
        loadFont("/fonts/Ubuntu-Bold.ttf");

        // Register TwelveMonkeys WebP reader (needed in fat JAR where SPI may not auto-discover)
        ImageIO.scanForPlugins();
        try {
            Class.forName("com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi");
            javax.imageio.spi.IIORegistry registry = javax.imageio.spi.IIORegistry.getDefaultInstance();
            registry.registerServiceProvider(
                new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
        } catch (Exception e) {
            System.err.println("WebP plugin registration: " + e.getMessage());
        }

        accountPopup = popupCard();
        profilePopup = popupCard();
        popupLayer   = new AnchorPane(accountPopup, profilePopup);
        popupLayer.setPickOnBounds(false);

        buildShell(stage);

        buildMain(stage);
        buildSettings(stage);
        buildInstall();
        buildProfileList();
        buildProfileEdit(null);
        buildContentManager(null);
        buildLog();
        buildOverlay();

        navigateTo(slotMain, sideHomeBtn);

        rootStack = new StackPane(shell, slotOverlay, popupLayer);
        rootStack.setStyle("-fx-background-color:#111111;");

        Scene scene = new Scene(rootStack, 1060, 640);
        try { scene.getStylesheets().add(
                getClass().getResource("/style.css").toExternalForm()); }
        catch (Exception e) { System.err.println("CSS: " + e.getMessage()); }

        stage.setTitle("OnyxLauncher");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (!isGameRunning) { Platform.exit(); System.exit(0); }
            else { e.consume(); hideToTray(stage); }
        });
        stage.show();
    }



    
    private void navigateTo(StackPane slot, Button sideBtn) {
        accountPopup.setVisible(false);
        profilePopup.setVisible(false);
        if (sideBtn != null) setSideActive(sideBtn);
        animatePageTransition(slot);
    }

    private void showPage(StackPane slot) {
        accountPopup.setVisible(false);
        profilePopup.setVisible(false);
        animatePageTransition(slot);
    }

    private void animatePageTransition(StackPane next) {
        if (pageArea.getChildren().isEmpty()) {

            next.setVisible(true);
            pageArea.getChildren().setAll(next);
            return;
        }

        Node current = pageArea.getChildren().get(0);

        if (current == next) return;

        javafx.animation.FadeTransition fadeOut =
            new javafx.animation.FadeTransition(Duration.millis(90), current);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);

        fadeOut.setOnFinished(e -> {

            next.setVisible(true);
            next.setOpacity(0);
            next.setTranslateY(10);
            pageArea.getChildren().setAll(next);

            javafx.animation.FadeTransition fadeIn =
                new javafx.animation.FadeTransition(Duration.millis(160), next);
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);

            javafx.animation.TranslateTransition slideUp =
                new javafx.animation.TranslateTransition(Duration.millis(160), next);
            slideUp.setFromY(10);
            slideUp.setToY(0);
            slideUp.setInterpolator(javafx.animation.Interpolator.EASE_OUT);

            javafx.animation.ParallelTransition enter =
                new javafx.animation.ParallelTransition(fadeIn, slideUp);
            enter.play();
        });

        fadeOut.play();
    }

    private static StackPane slot() {
        StackPane sp = new StackPane();
        sp.setVisible(false);
        return sp;
    }

    private static void setSlot(StackPane slot, Node content) {
        slot.getChildren().setAll(content);
    }



    private void hover(Node n) {
        ScaleTransition i = new ScaleTransition(Duration.millis(100), n);
        i.setToX(1.04); i.setToY(1.04);
        ScaleTransition o = new ScaleTransition(Duration.millis(100), n);
        o.setToX(1.0);  o.setToY(1.0);
        n.setOnMouseEntered(e -> i.playFromStart());
        n.setOnMouseExited (e -> o.playFromStart());
    }

    private void positionAbove(VBox popup, Button btn) {
        javafx.geometry.Bounds b = btn.localToScene(btn.getBoundsInLocal());
        double h = popup.getHeight() > 10 ? popup.getHeight() : 180;
        AnchorPane.setLeftAnchor(popup, b.getMinX());
        AnchorPane.setTopAnchor (popup, b.getMinY() - h - 6);
    }

    private void positionBelow(VBox popup, Button btn) {
        javafx.geometry.Bounds b = btn.localToScene(btn.getBoundsInLocal());
        AnchorPane.setLeftAnchor(popup, b.getMinX());
        AnchorPane.setTopAnchor (popup, b.getMaxY() + 6);
    }

    private BorderPane shell;
    private StackPane  pageArea;

    private Button sideHomeBtn;
    private Button sideProfilesBtn;
    private Button sideInstallBtn;
    private Button sideSettingsBtn;
    private Button sideLogBtn;
    private Button sideAccountBtn;

    private Button activeSideBtn = null;
    private Button sideContentBtn;



    private void buildShell(Stage stage) {

        VBox sidebar = new VBox(4);
        sidebar.getStyleClass().add("sidebar");

        sideHomeBtn     = sideNavBtn(ICON_HOME,     "Home",             true);
        sideProfilesBtn = sideNavBtn(ICON_PROFILES, "Profiles",         false);
        sideInstallBtn  = sideNavBtn(ICON_INSTALL,  "Install Minecraft", false);
        sideContentBtn  = sideNavBtn(ICON_CONTENT,  "Get Content",       false);
        sideLogBtn      = sideNavBtn(ICON_TERMINAL, "Log",              false);
        sideSettingsBtn = sideNavBtn(ICON_SETTINGS, "Settings",         false);

        Region sideSpacer = new Region();
        VBox.setVgrow(sideSpacer, Priority.ALWAYS);

        sideAccountBtn = buildAccountCard();

        sidebar.getChildren().addAll(
            sideHomeBtn, sideProfilesBtn, sideInstallBtn, sideContentBtn,
            sideLogBtn, sideSettingsBtn,
            sideSpacer,
            sideAccountBtn
        );
        activeSideBtn = sideHomeBtn;
        tintSvgGroup(svgGroupOf(sideHomeBtn), "#1db954");

        sideHomeBtn.setOnAction(e     -> navigateTo(slotMain,          sideHomeBtn));
        sideProfilesBtn.setOnAction(e -> { buildProfileList(); navigateTo(slotProfileList, sideProfilesBtn); });
        sideInstallBtn.setOnAction(e  -> navigateTo(slotInstall,       sideInstallBtn));
        sideContentBtn.setOnAction(e  -> { buildContentBrowser(); navigateTo(slotContentBrowser, sideContentBtn); });
        sideLogBtn.setOnAction(e      -> navigateTo(slotLog,           sideLogBtn));
        sideSettingsBtn.setOnAction(e -> navigateTo(slotSettings,      sideSettingsBtn));
        sideAccountBtn.setOnAction(e  -> {
            boolean open = !accountPopup.isVisible();
            profilePopup.setVisible(false);
            accountPopup.setVisible(open);
            if (open) positionAboveSidebar(accountPopup, sideAccountBtn);
        });

        pageArea = new StackPane();
        pageArea.setStyle("-fx-background-color:#111111;");

        shell = new BorderPane();
        shell.setLeft(sidebar);
        shell.setCenter(pageArea);
    }

    private Button buildAccountCard() {
        String name = settings.getUsername().isEmpty() ? "No Account" : settings.getUsername();

        Label dot = new Label("●");
        dot.setStyle("-fx-text-fill:#1db954;-fx-font-size:8px;");

        Label nameLbl = new Label(name);
        nameLbl.setStyle(
            "-fx-text-fill:#dddddd;" +
            "-fx-font-size:13px;" +
            "-fx-font-weight:bold;" +
            "-fx-font-family:'Ubuntu';"
        );
        nameLbl.setMaxWidth(130);
        nameLbl.setEllipsisString("…");

        Label statusLbl = new Label("Online");
        statusLbl.setStyle(
            "-fx-text-fill:#1db954;" +
            "-fx-font-size:10px;" +
            "-fx-font-family:'Ubuntu';"
        );

        Label avatar = new Label(name.isEmpty() ? "?" : String.valueOf(name.charAt(0)).toUpperCase());
        avatar.setStyle(
            "-fx-background-color:#1db954;" +
            "-fx-text-fill:white;" +
            "-fx-font-size:14px;" +
            "-fx-font-weight:bold;" +
            "-fx-font-family:'Ubuntu';" +
            "-fx-min-width:36;-fx-min-height:36;" +
            "-fx-max-width:36;-fx-max-height:36;" +
            "-fx-alignment:center;" +
            "-fx-background-radius:8;"
        );

        VBox textBox = new VBox(2, nameLbl, statusLbl);
        textBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox inner = new HBox(10, avatar, textBox);
        inner.setAlignment(Pos.CENTER_LEFT);
        inner.setMouseTransparent(true);

        Button b = new Button();
        b.setGraphic(inner);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle(
            "-fx-background-color:#161616;" +
            "-fx-background-radius:10;" +
            "-fx-border-color:#222222;" +
            "-fx-border-radius:10;" +
            "-fx-border-width:1;" +
            "-fx-padding:10 12;" +
            "-fx-cursor:hand;"
        );
        b.setOnMouseEntered(ev -> b.setStyle(
            "-fx-background-color:#1e1e1e;" +
            "-fx-background-radius:10;" +
            "-fx-border-color:#2a2a2a;" +
            "-fx-border-radius:10;" +
            "-fx-border-width:1;" +
            "-fx-padding:10 12;" +
            "-fx-cursor:hand;"
        ));
        b.setOnMouseExited(ev -> b.setStyle(
            "-fx-background-color:#161616;" +
            "-fx-background-radius:10;" +
            "-fx-border-color:#222222;" +
            "-fx-border-radius:10;" +
            "-fx-border-width:1;" +
            "-fx-padding:10 12;" +
            "-fx-cursor:hand;"
        ));
        VBox.setMargin(b, new Insets(0, 6, 10, 6));
        return b;
    }

    private void refreshAccountCard() {
        if (sideAccountBtn == null) return;
        String name = settings.getUsername().isEmpty() ? "No Account" : settings.getUsername();
        if (sideAccountBtn.getGraphic() instanceof HBox hb) {

            if (hb.getChildren().get(0) instanceof Label av) {
                av.setText(name.isEmpty() ? "?" : String.valueOf(name.charAt(0)).toUpperCase());
            }

            if (hb.getChildren().get(1) instanceof VBox vb
                    && vb.getChildren().get(0) instanceof Label nl) {
                nl.setText(name);
            }
        }
    }

    private void positionAboveSidebar(VBox popup, Button btn) {
        javafx.geometry.Bounds b = btn.localToScene(btn.getBoundsInLocal());
        double h = popup.getHeight() > 10 ? popup.getHeight() : 200;
        AnchorPane.setLeftAnchor(popup, b.getMaxX() + 8);
        AnchorPane.setTopAnchor (popup, b.getMinY() - h + btn.getHeight());
    }

    private void buildMain(Stage stage) {
        BorderPane page = new BorderPane();
        page.setStyle("-fx-background-color:#0c0c0c;");


        Pane bannerBg = new Pane();
        bannerBg.setPrefHeight(220);
        bannerBg.setMinHeight(220);
        bannerBg.setMaxHeight(220);
        bannerBg.setStyle(
            "-fx-background-color: linear-gradient(" +
            "from 0% 0% to 0% 100%," +
            "#1a1a2e 0%," +
            "#16213e 30%," +
            "#0f3460 60%," +
            "#0a0a0a 100%);"
        );


        heroBadgeLbl = new Label("● CURRENT PROFILE");
        heroBadgeLbl.setStyle(
            "-fx-text-fill:" + activeProfile.getColor() + ";" +
            "-fx-font-size:10px;-fx-font-weight:bold;" +
            "-fx-font-family:'Ubuntu';" +
            "-fx-background-color:" + activeProfile.getColor() + "33;" +
            "-fx-padding:3 10;-fx-background-radius:4;"
        );

        heroNameLbl = new Label(activeProfile.getName());
        heroNameLbl.setStyle(
            "-fx-text-fill:#ffffff;" +
            "-fx-font-size:26px;" +
            "-fx-font-weight:bold;" +
            "-fx-font-family:'Ubuntu';" +
            "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.8),8,0,0,2);"
        );

        String rawVer = activeProfile.getVersion();
        String vType  = rawVer.startsWith("fabric-loader") ? "Fabric"
                      : rawVer.contains("forge") ? "Forge" : "Vanilla";
        String vShort = rawVer.startsWith("fabric-loader")
                      ? rawVer.replaceAll("^fabric-loader-[^-]+-","") : rawVer;
        heroVerLbl = new Label(vType + (vShort.isEmpty() ? "" : "  •  " + vShort));
        heroVerLbl.setStyle(
            "-fx-text-fill:#aaaaaa;" +
            "-fx-font-size:13px;" +
            "-fx-font-family:'Ubuntu';"
        );

        VBox leftInfo = new VBox(5, heroBadgeLbl, heroNameLbl, heroVerLbl);
        leftInfo.setAlignment(Pos.CENTER_LEFT);

        profileBtn = btn(activeProfile.getName() + "  ▾","btn","btn-profile");
        profileBtn.setStyle(
            "-fx-background-color:" + activeProfile.getColor() + ";" +
            "-fx-text-fill:white;" +
            "-fx-font-weight:bold;" +
            "-fx-font-size:13px;" +
            "-fx-font-family:'Ubuntu';" +
            "-fx-background-radius:8;" +
            "-fx-pref-height:42;" +
            "-fx-padding:0 16;"
        );

        Button playBtn = new Button("▶   PLAY");
        playBtn.setStyle(
            "-fx-background-color:#1db954;" +
            "-fx-text-fill:white;" +
            "-fx-font-weight:bold;" +
            "-fx-font-size:16px;" +
            "-fx-font-family:'Ubuntu';" +
            "-fx-background-radius:10;" +
            "-fx-pref-height:50;" +
            "-fx-pref-width:155;" +
            "-fx-cursor:hand;" +
            "-fx-effect:dropshadow(gaussian,rgba(29,185,84,0.4),12,0,0,3);"
        );
        hover(profileBtn); hover(playBtn);

        Region sp1 = new Region(); HBox.setHgrow(sp1, Priority.ALWAYS);
        HBox rightControls = new HBox(10, profileBtn, playBtn);
        rightControls.setAlignment(Pos.CENTER_RIGHT);

        HBox heroRow = new HBox(0, leftInfo, sp1, rightControls);
        heroRow.setAlignment(Pos.CENTER_LEFT);
        heroRow.setPadding(new Insets(0, 24, 22, 24));

        Pane gradOverlay = new Pane();
        gradOverlay.setStyle(
            "-fx-background-color: linear-gradient(" +
            "from 0% 0% to 0% 100%," +
            "transparent 0%," +
            "rgba(0,0,0,0.85) 100%);"
        );

        StackPane banner = new StackPane(bannerBg, gradOverlay);
        banner.setPrefHeight(220);
        banner.setMinHeight(220);
        banner.setMaxHeight(220);

        gradOverlay.prefWidthProperty().bind(banner.widthProperty());
        gradOverlay.prefHeightProperty().bind(banner.heightProperty());

        BorderPane bannerPane = new BorderPane();
        bannerPane.setCenter(banner);
        bannerPane.setBottom(heroRow);
        bannerPane.setStyle("-fx-background-color:transparent;");

        page.setTop(bannerPane);

        StackPane centre = new StackPane();
        centre.setStyle("-fx-background-color:#0c0c0c;");
        page.setCenter(centre);

        refreshAccountPopup();
        refreshProfilePopup();

        profileBtn.setOnAction(e -> {
            boolean open = !profilePopup.isVisible();
            accountPopup.setVisible(false); profilePopup.setVisible(open);
            if (open) positionBelow(profilePopup, profileBtn);
        });
        page.setOnMouseClicked(e -> { accountPopup.setVisible(false); profilePopup.setVisible(false); });

        playBtn.setOnAction(e -> {
            String user = settings.getUsername();
            if (user.isEmpty()) { alert("No account", "Select an account first."); return; }
            if (activeProfile.getVersion().isEmpty()) { alert("No version", "Edit the profile and set a Minecraft version."); return; }
            playBtn.setText("LAUNCHING…"); playBtn.setDisable(true);
            new Thread(() -> { try {
                Process proc = GameRunner.launchGame(user, activeProfile);
                if (proc != null) {
                    isGameRunning = true;
                    Platform.runLater(() -> hideToTray(stage));
                    new BufferedReader(new InputStreamReader(proc.getInputStream()))
                        .lines().forEach(ln -> System.out.println("[MC] " + ln));
                    proc.waitFor(); isGameRunning = false;
                    Platform.runLater(() -> { restoreFromTray(stage); playBtn.setText("▶   PLAY"); playBtn.setDisable(false); });
                }
            } catch (Exception ex) { ex.printStackTrace();
                Platform.runLater(() -> { playBtn.setText("▶   PLAY"); playBtn.setDisable(false); });
            }}).start();
        });

        setSlot(slotMain, page);
    }

    private void refreshMainHero() { }

    private void refreshProfileBtn() {
        // update profile switcher button
        profileBtn.setText(activeProfile.getName() + "  ▾");
        profileBtn.setStyle(
            "-fx-background-color:" + activeProfile.getColor() + ";" +
            "-fx-text-fill:white;" +
            "-fx-font-weight:bold;" +
            "-fx-font-size:14px;" +
            "-fx-font-family:'Ubuntu';" +
            "-fx-background-radius:8;" +
            "-fx-pref-height:44;" +
            "-fx-padding:0 18;"
        );

        // update hero banner labels
        if (heroBadgeLbl != null) {
            heroBadgeLbl.setStyle(
                "-fx-text-fill:" + activeProfile.getColor() + ";" +
                "-fx-font-size:10px;-fx-font-weight:bold;" +
                "-fx-font-family:'Ubuntu';" +
                "-fx-background-color:" + activeProfile.getColor() + "33;" +
                "-fx-padding:3 10;-fx-background-radius:4;"
            );
        }
        if (heroNameLbl != null) {
            heroNameLbl.setText(activeProfile.getName());
        }
        if (heroVerLbl != null) {
            String rv = activeProfile.getVersion();
            String vt = rv.startsWith("fabric-loader") ? "Fabric"
                      : rv.contains("forge") ? "Forge" : "Vanilla";
            String vs = rv.startsWith("fabric-loader")
                      ? rv.replaceAll("^fabric-loader-[^-]+-","") : rv;
            heroVerLbl.setText(vt + (vs.isEmpty() ? "" : "  •  " + vs));
        }
    }



    private void refreshAccountPopup() {
        accountPopup.getChildren().clear();
        Label h = new Label("Accounts"); h.getStyleClass().add("popup-header");
        accountPopup.getChildren().add(h);

        for (String u : settings.getUsernameHistory()) {
            boolean active = u.equals(settings.getUsername());

            Button b = popupItem(u, active ? "#1db954" : "#ffffff");
            b.setOnAction(e -> {
                settings.setUsername(u);
                settings.save();
                refreshAccountCard();
                accountPopup.setVisible(false);
                refreshAccountPopup();
            });

            Button del = new Button("✕");
            del.setStyle(
                "-fx-background-color:transparent;" +
                "-fx-text-fill:#555;" +
                "-fx-font-size:11px;" +
                "-fx-padding:2 6;" +
                "-fx-cursor:hand;"
            );
            del.setOnMouseEntered(ev -> del.setStyle(
                "-fx-background-color:transparent;" +
                "-fx-text-fill:#e53935;" +
                "-fx-font-size:11px;" +
                "-fx-padding:2 6;" +
                "-fx-cursor:hand;"
            ));
            del.setOnMouseExited(ev -> del.setStyle(
                "-fx-background-color:transparent;" +
                "-fx-text-fill:#555;" +
                "-fx-font-size:11px;" +
                "-fx-padding:2 6;" +
                "-fx-cursor:hand;"
            ));
            del.setOnAction(e -> {
                settings.getUsernameHistory().remove(u);

                if (active) {
                    String next = settings.getUsernameHistory().isEmpty()
                        ? "" : settings.getUsernameHistory().get(0);
                    settings.setUsername(next);
                    refreshAccountCard();
                }
                settings.save();
                refreshAccountPopup();
            });

            HBox row = new HBox(del);
            row.setAlignment(Pos.CENTER_RIGHT);
            HBox.setHgrow(b, Priority.ALWAYS);
            b.setMaxWidth(Double.MAX_VALUE);
            HBox item = new HBox(0, b, del);
            item.setAlignment(Pos.CENTER_LEFT);
            accountPopup.getChildren().add(item);
        }

        accountPopup.getChildren().add(new Separator());
        Button add = popupItem("+ Add Account", "#1db954");
        add.setOnAction(e -> { accountPopup.setVisible(false); openAddAccount(); });
        accountPopup.getChildren().add(add);
    }



    private void refreshProfilePopup() {
        profilePopup.getChildren().clear();
        Label h=new Label("Profiles"); h.getStyleClass().add("popup-header");
        profilePopup.getChildren().add(h);
        for(Profile p:pm.getAll()){
            boolean active=p.getId().equals(activeProfile.getId());
            String label=(active?"● ":"○ ")+p.getName()
                +(p.getVersion().isEmpty()?"":" ["+p.getVersion()+"]");
            Button b=popupItem(label, active?p.getColor():"#cccccc");
            b.setOnAction(e->{
                activeProfile=p; settings.setActiveProfileId(p.getId()); settings.save();
                refreshProfileBtn(); profilePopup.setVisible(false); refreshProfilePopup();
            });
            profilePopup.getChildren().add(b);
        }
        profilePopup.getChildren().add(new Separator());
        Button addNew=popupItem("+ New Profile","#1db954");
        addNew.setOnAction(e->{
            profilePopup.setVisible(false);
            Profile fresh=Profile.createDefault("New Profile"); pm.upsert(fresh);
            buildProfileEdit(fresh); showPage(slotProfileEdit);
        });
        profilePopup.getChildren().add(addNew);
    }



    private void buildLog() {
        BorderPane page = new BorderPane();
        page.setStyle("-fx-background-color:#0a0a0a;");


        javafx.scene.Node termIcon = svgIcon(ICON_TERMINAL);
        tintSvgGroup(termIcon, "#1db954");
        StackPane termIconBox = new StackPane(termIcon);
        termIconBox.setMinSize(22, 22); termIconBox.setMaxSize(22, 22);

        Label titleLbl = new Label("Log");
        titleLbl.setStyle(
            "-fx-text-fill:#ffffff;" +
            "-fx-font-size:16px;" +
            "-fx-font-weight:bold;" +
            "-fx-font-family:'Ubuntu';"
        );

        Button clearBtn = btn("Clear", "btn", "btn-dark"); hover(clearBtn);
        clearBtn.setStyle("-fx-font-size:12px;-fx-padding:4 12;");

        HBox hdr = new HBox(10, termIconBox, titleLbl);
        hdr.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(hdr, Priority.ALWAYS);
        BorderPane topBar = new BorderPane();
        topBar.setLeft(hdr);
        topBar.setRight(clearBtn);
        topBar.setStyle(
            "-fx-background-color:#0f0f0f;" +
            "-fx-padding:12 16 12 16;" +
            "-fx-border-color:#1a1a1a;" +
            "-fx-border-width:0 0 1 0;"
        );
        page.setTop(topBar);

        debugArea = new TextArea();
        debugArea.setEditable(false);
        debugArea.getStyleClass().add("log-area");
        debugArea.setStyle(
            "-fx-control-inner-background:#080808;" +
            "-fx-text-fill:#33ff66;" +
            "-fx-font-family:'Ubuntu Mono','Cascadia Code','Consolas','Courier New',monospace;" +
            "-fx-font-size:12px;" +
            "-fx-border-color:transparent;" +
            "-fx-background-color:#080808;"
        );

        OutputStream lo = new OutputStream() {
            public void write(int b) { synchronized(logBuf){logBuf.append((char)b);} }
            public void write(byte[] b,int o,int l){synchronized(logBuf){logBuf.append(new String(b,o,l));}}
        };
        System.setOut(new PrintStream(lo,true));
        System.setErr(new PrintStream(lo,true));

        Timeline lt = new Timeline(new KeyFrame(Duration.millis(150), ev -> {
            String c; synchronized(logBuf){if(logBuf.isEmpty())return; c=logBuf.toString(); logBuf.setLength(0);}
            debugArea.appendText(c);
            if(debugArea.getLength()>60_000) debugArea.deleteText(0,debugArea.getLength()-30_000);
        }));
        lt.setCycleCount(Timeline.INDEFINITE); lt.play();

        clearBtn.setOnAction(e -> debugArea.clear());

        ScrollPane scroll = new ScrollPane(debugArea);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setStyle("-fx-background-color:#080808;-fx-border-color:transparent;");
        page.setCenter(scroll);

        setSlot(slotLog, page);
    }



    private void buildOverlay() {
        VBox backdrop = new VBox();
        backdrop.getStyleClass().add("overlay-backdrop");
        backdrop.setAlignment(Pos.CENTER);
        backdrop.setOnMouseClicked(e->{if(e.getTarget()==backdrop) slotOverlay.setVisible(false);});
        setSlot(slotOverlay, backdrop);
    }

    private void openAddAccount() {
        VBox backdrop = (VBox) slotOverlay.getChildren().get(0);
        backdrop.getChildren().clear();

        VBox dlg = new VBox(16);
        dlg.getStyleClass().add("overlay-dialog");
        dlg.setAlignment(Pos.CENTER);

        Label title=new Label("Add Account"); title.getStyleClass().add("dialog-title");
        TextField field=new TextField(); field.setPromptText("Username");
        Button ok=btn("Add Account","btn","btn-primary"); ok.setMaxWidth(Double.MAX_VALUE); ok.setPrefHeight(40);
        Button cancel=btn("Cancel","btn","btn-dark");     cancel.setMaxWidth(Double.MAX_VALUE);
        hover(ok); hover(cancel);

        ok.setOnAction(ev->{
            String name=field.getText().trim();
            if(!name.isEmpty()){
                settings.setUsername(name); settings.addUsernameToHistory(name); settings.save();
                refreshAccountCard(); refreshAccountPopup();
                slotOverlay.setVisible(false);
            }
        });
        cancel.setOnAction(ev->slotOverlay.setVisible(false));
        field.setOnAction(ev->ok.fire());

        dlg.getChildren().addAll(title,field,ok,cancel);
        backdrop.getChildren().add(dlg);
        slotOverlay.setVisible(true);
    }



    private void buildSettings(Stage stage) {
        VBox content = new VBox(24);
        content.setPadding(new Insets(28, 36, 36, 36));
        content.setStyle("-fx-background-color:#111111;");

        Label title = new Label("Settings");
        title.setStyle("-fx-text-fill:white;-fx-font-size:22px;-fx-font-weight:bold;");
        BorderPane hdr = new BorderPane(); hdr.setLeft(title);

        Label pathLbl = sLbl("Default .minecraft Path  (blank = auto-detect)");
        TextField pathF = field(settings.getCustomMcPath(), "Leave blank for default");
        Button browse = btn("Browse…", "btn", "btn-dark"); hover(browse);
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            File d = dc.showDialog(stage);
            if (d != null) pathF.setText(d.getAbsolutePath());
        });
        HBox pathRow = new HBox(8, pathF, browse);
        HBox.setHgrow(pathF, Priority.ALWAYS);

        Label gpuTitle = new Label("Graphics Card");
        gpuTitle.setStyle("-fx-text-fill:white;-fx-font-size:16px;-fx-font-weight:bold;");
        Label gpuSubtitle = new Label("Select which GPU every Minecraft launch will use");
        gpuSubtitle.setStyle("-fx-text-fill:#888;-fx-font-size:12px;");
        VBox gpuHeader = new VBox(3, gpuTitle, gpuSubtitle);

        List<String> rawGpus    = GameRunner.detectGpus();
        String       savedGpu   = settings.getGpuMode();
        ToggleGroup  gpuGroup   = new ToggleGroup();

        gpuGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) gpuGroup.selectToggle(oldT);
        });
        List<ToggleButton> gpuBtns = new ArrayList<>();

        FlowPane gpuCards = new FlowPane(14, 14);
        gpuCards.setStyle("-fx-padding:4 0 0 0;");

        for (String entry : rawGpus) {
            String[] parts     = entry.split("\\|", 2);
            String   typeKey   = parts[0];
            String   rawName   = parts.length > 1 ? parts[1] : typeKey;

            rawName = rawName
                .replaceAll("(?i)^Intel Corporation\\s+", "")
                .replaceAll("(?i)^NVIDIA Corporation\\s+", "")
                .replaceAll("(?i)^Advanced Micro Devices.*?\\]\\s*", "");
            if (rawName.length() > 52) rawName = rawName.substring(0, 52) + "...";
            final String gpuName = rawName;

            boolean isDiscrete = typeKey.equals("dgpu");
            String  accentCol  = isDiscrete ? "#1db954" : "#2196F3";
            String  typeLabel  = isDiscrete ? "Discrete GPU" : "Integrated GPU";

            ToggleButton tb = new ToggleButton();
            tb.setToggleGroup(gpuGroup);
            tb.setUserData(entry);
            tb.setCursor(javafx.scene.Cursor.HAND);
            tb.setFocusTraversable(true);

            Label nameLbl = new Label(gpuName);
            nameLbl.setStyle("-fx-text-fill:#efefef;-fx-font-size:13px;-fx-font-weight:bold;");
            nameLbl.setWrapText(true);
            nameLbl.setMaxWidth(220);

            Label typeLbl = new Label(typeLabel);
            typeLbl.setStyle(
                "-fx-text-fill:" + accentCol + ";" +
                "-fx-font-size:11px;-fx-font-weight:bold;" +
                "-fx-background-color:" + accentCol + "22;" +
                "-fx-padding:2 8;-fx-background-radius:4;"
            );

            VBox inner = new VBox(7, nameLbl, typeLbl);
            inner.setAlignment(Pos.CENTER_LEFT);
            inner.setMouseTransparent(true);
            tb.setGraphic(inner);
            tb.setMinWidth(220);
            tb.setPrefHeight(68);

            boolean hasDgpu = rawGpus.stream().anyMatch(g -> g.startsWith("dgpu|"));
            boolean sel;
            if (savedGpu.equals("auto") || savedGpu.isEmpty()) {
                if (hasDgpu) {
                    sel = isDiscrete && rawGpus.stream()
                            .filter(g -> g.startsWith("dgpu|"))
                            .findFirst().map(g -> g.equals(entry)).orElse(false);
                } else {
                    sel = !isDiscrete && rawGpus.stream()
                            .filter(g -> g.startsWith("igpu|"))
                            .findFirst().map(g -> g.equals(entry)).orElse(false);
                }
            } else {
                sel = savedGpu.equals(entry)
                    || (savedGpu.startsWith("dgpu") && isDiscrete
                        && rawGpus.stream().filter(g -> g.startsWith("dgpu|"))
                                  .findFirst().map(g -> g.equals(entry)).orElse(false))
                    || (savedGpu.startsWith("igpu") && !isDiscrete
                        && rawGpus.stream().filter(g -> g.startsWith("igpu|"))
                                  .findFirst().map(g -> g.equals(entry)).orElse(false));
            }
            if (sel) tb.setSelected(true);

            final String ac = accentCol;

            tb.selectedProperty().addListener((obs, was, now) -> {
                for (ToggleButton b : gpuBtns) {
                    boolean s  = b.isSelected();
                    String  ba = s ? (((String) b.getUserData()).startsWith("dgpu") ? "#1db954" : "#2196F3") : "#2a2a2a";
                    b.setStyle(
                        "-fx-background-color:" + (s ? "#0e2018" : "#191919") + ";" +
                        "-fx-border-color:" + ba + ";" +
                        "-fx-border-width:2;-fx-border-radius:8;" +
                        "-fx-background-radius:8;-fx-padding:12 18;"
                    );
                }
            });

            gpuBtns.add(tb);
            gpuCards.getChildren().add(tb);
        }

        for (ToggleButton b : gpuBtns) {
            boolean s  = b.isSelected();
            String  ba = s ? (((String) b.getUserData()).startsWith("dgpu") ? "#1db954" : "#2196F3") : "#2a2a2a";
            b.setStyle(
                "-fx-background-color:" + (s ? "#0e2018" : "#191919") + ";" +
                "-fx-border-color:" + ba + ";" +
                "-fx-border-width:2;-fx-border-radius:8;" +
                "-fx-background-radius:8;-fx-padding:12 18;"
            );
        }

        Label advTitle = new Label("Install Options");
        advTitle.setStyle("-fx-text-fill:white;-fx-font-size:16px;-fx-font-weight:bold;");
        Label advSub = new Label("Show legacy versions in the Install tab");
        advSub.setStyle("-fx-text-fill:#888;-fx-font-size:12px;");
        CheckBox advCheck = new CheckBox("Show Snapshot, Old Beta & Old Alpha versions");
        advCheck.setSelected(settings.isShowAdvancedVersions());

        Button save = btn("Save & Back", "btn", "btn-primary");
        save.setPrefHeight(44); save.setPrefWidth(160); hover(save);
        save.setOnAction(e -> {
            settings.setCustomMcPath(pathF.getText().trim());
            Toggle selGpu = gpuGroup.getSelectedToggle();
            if (selGpu != null) settings.setGpuMode((String) selGpu.getUserData());
            settings.setShowAdvancedVersions(advCheck.isSelected());
            settings.save();
            buildInstall();
            navigateTo(slotMain, sideHomeBtn);
        });

        content.getChildren().addAll(
            hdr,
            new Separator(),
            pathLbl, pathRow,
            new Separator(),
            gpuHeader, gpuCards,
            new Separator(),
            advTitle, advSub, advCheck,
            new Separator(),
            save
        );
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("scroll-pane");
        setSlot(slotSettings, sp);
    }



    private void buildInstall() {
        VBox root=new VBox(0); root.setStyle("-fx-background-color:#111111;");

        Label title=new Label("Install Minecraft"); title.setStyle("-fx-text-fill:white;-fx-font-size:20px;-fx-font-weight:bold;");
        BorderPane hdr=new BorderPane(); hdr.setLeft(title);
        hdr.getStyleClass().add("page-header"); hdr.setPadding(new Insets(18,24,14,24));

        installTabPane=new TabPane();
        installTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        installTabPane.getTabs().addAll(
            versionTab("Release","release"),
            fabricTab(),
            forgeTab()
        );
        if (settings.isShowAdvancedVersions()) {
            installTabPane.getTabs().addAll(
                versionTab("Snapshot","snapshot"),
                versionTab("Old Beta","old_beta"),
                versionTab("Old Alpha","old_alpha")
            );
        }

        VBox.setVgrow(installTabPane,Priority.ALWAYS);
        root.getChildren().addAll(hdr,installTabPane);
        setSlot(slotInstall, root);
    }

    private Tab versionTab(String label, String type) {
        Tab tab=new Tab(label);
        VBox box=new VBox(0); box.setStyle("-fx-background-color:#111111;");

        ProgressBar pb=new ProgressBar(0); pb.setPrefWidth(Double.MAX_VALUE); pb.setVisible(false);
        pb.getStyleClass().add("progress-bar");
        Label st=new Label(); st.getStyleClass().add("label-hint"); st.setStyle("-fx-padding:4 24;"); st.setVisible(false);

        ListView<String> lv=new ListView<>(); lv.getStyleClass().add("list-view"); VBox.setVgrow(lv,Priority.ALWAYS);
        Label loading=new Label("Loading…"); loading.setStyle("-fx-text-fill:#555;-fx-font-size:14px;");
        StackPane center=new StackPane(lv,loading); VBox.setVgrow(center,Priority.ALWAYS);

        Button installBtn=btn("Install Selected","btn","btn-primary");
        installBtn.setMaxWidth(Double.MAX_VALUE); installBtn.setPrefHeight(44);
        installBtn.setDisable(true); hover(installBtn);
        VBox.setMargin(installBtn,new Insets(10,24,16,24));
        box.getChildren().addAll(center,pb,st,installBtn); tab.setContent(box);

        tab.selectedProperty().addListener((ob,o,n)->{
            if(n&&lv.getItems().isEmpty()){
                loading.setVisible(true); lv.setVisible(false);
                new Thread(()->{try{
                    List<String> vs=MinecraftInstaller.fetchVersionsByType(type);
                    Platform.runLater(()->{lv.setItems(FXCollections.observableArrayList(vs)); lv.setVisible(true); loading.setVisible(false); installBtn.setDisable(false);});
                }catch(Exception ex){Platform.runLater(()->loading.setText("Failed: "+ex.getMessage()));}}).start();
            }
        });

        installBtn.setOnAction(e->{
            String sel=lv.getSelectionModel().getSelectedItem();
            if(sel==null){alert("","Select a version first."); return;}
            installBtn.setDisable(true); pb.setProgress(ProgressBar.INDETERMINATE_PROGRESS); pb.setVisible(true); st.setVisible(true);
            new Thread(()->{try{
                MinecraftInstaller.installVersion(sel,(msg,pct)->Platform.runLater(()->{st.setText(msg);if(pct>=0)pb.setProgress(pct/100.0);}));
                Platform.runLater(()->{pb.setProgress(1.0); st.setText("Done: "+sel); installBtn.setDisable(false);
                    activeProfile.setVersion(sel); pm.upsert(activeProfile); refreshProfileBtn();
                    alert("Installed!",sel+" installed.\nClick LAUNCH to play!"); navigateTo(slotMain, sideHomeBtn);});
            }catch(Exception ex){ex.printStackTrace(); Platform.runLater(()->{st.setText("Error: "+ex.getMessage()); pb.setProgress(0); installBtn.setDisable(false);});}}).start();
        });
        return tab;
    }

    private Tab fabricTab() {
        Tab tab=new Tab("Fabric");
        VBox box=new VBox(14); box.setPadding(new Insets(18,24,18,24)); box.setStyle("-fx-background-color:#111111;");

        installMcCombo=new ComboBox<>(); installMcCombo.setPrefWidth(280); installMcCombo.setPromptText("Minecraft Version…"); installMcCombo.setVisibleRowCount(8); limitComboHeight(installMcCombo, 8);
        installLoaderCombo=new ComboBox<>(); installLoaderCombo.setPrefWidth(280); installLoaderCombo.setPromptText("Loader Version…"); installLoaderCombo.setVisibleRowCount(8); limitComboHeight(installLoaderCombo, 8);
        installBar=new ProgressBar(0); installBar.setPrefWidth(Double.MAX_VALUE); installBar.setVisible(false);
        installBar.getStyleClass().addAll("progress-bar","fabric");
        installStatus=new Label(); installStatus.getStyleClass().add("label-hint"); installStatus.setVisible(false);
        installStartBtn=btn("Install Fabric","btn","btn-fabric");
        installStartBtn.setMaxWidth(Double.MAX_VALUE); installStartBtn.setPrefHeight(44); installStartBtn.setDisable(true); hover(installStartBtn);
        installStartBtn.setOnAction(e->startFabricInstall());

        box.getChildren().addAll(sLbl("Minecraft Version"),installMcCombo,
            sLbl("Fabric Loader Version"),installLoaderCombo,installBar,installStatus,installStartBtn);
        tab.setContent(box);

        tab.selectedProperty().addListener((ob,o,n)->{if(n){
            if(installMcCombo.getItems().isEmpty()){ installStartBtn.setDisable(true);
                new Thread(()->{try{List<String> vs=MinecraftInstaller.fetchReleaseVersions();
                    Platform.runLater(()->{installMcCombo.setItems(FXCollections.observableArrayList(vs)); if(!vs.isEmpty())installMcCombo.getSelectionModel().selectFirst(); installStartBtn.setDisable(false);});
                }catch(Exception ex){Platform.runLater(()->installStatus.setText("Failed: "+ex.getMessage()));}}).start();}
            if(installLoaderCombo.getItems().isEmpty()){
                new Thread(()->{try{List<String> ls=FabricInstaller.fetchLoaderVersions();
                    Platform.runLater(()->{installLoaderCombo.setItems(FXCollections.observableArrayList(ls)); if(!ls.isEmpty())installLoaderCombo.getSelectionModel().selectFirst();});
                }catch(Exception ignored){}}).start();}
        }});
        return tab;
    }

    private Tab forgeTab() {
        Tab tab = new Tab("Forge");
        VBox box = new VBox(14);
        box.setPadding(new Insets(18, 24, 18, 24));
        box.setStyle("-fx-background-color:#111111;");

        ComboBox<String> mcCombo = new ComboBox<>();
        mcCombo.setPrefWidth(280);
        mcCombo.setPromptText("Minecraft Version…");
        mcCombo.setVisibleRowCount(8);
        limitComboHeight(mcCombo, 8);

        ComboBox<String> forgeCombo = new ComboBox<>();
        forgeCombo.setPrefWidth(280);
        forgeCombo.setPromptText("Forge Version…");
        forgeCombo.setVisibleRowCount(8);
        limitComboHeight(forgeCombo, 8);
        forgeCombo.setDisable(true);

        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(Double.MAX_VALUE);
        bar.setVisible(false);
        bar.getStyleClass().add("progress-bar");

        Label status = new Label();
        status.getStyleClass().add("label-hint");
        status.setVisible(false);

        Button installBtn = btn("Install Forge", "btn", "btn-primary");
        installBtn.setMaxWidth(Double.MAX_VALUE);
        installBtn.setPrefHeight(44);
        installBtn.setDisable(true);
        hover(installBtn);

        box.getChildren().addAll(
            sLbl("Minecraft Version"), mcCombo,
            sLbl("Forge Version"), forgeCombo,
            bar, status, installBtn
        );
        tab.setContent(box);

        tab.selectedProperty().addListener((ob, o, n) -> {
            if (n && mcCombo.getItems().isEmpty()) {
                new Thread(() -> {
                    try {
                        List<String> vers = ForgeInstaller.fetchSupportedMcVersions();
                        Platform.runLater(() -> {
                            mcCombo.setItems(FXCollections.observableArrayList(vers));
                            if (!vers.isEmpty()) mcCombo.getSelectionModel().selectFirst();
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> status.setText("Failed to load versions: " + ex.getMessage()));
                    }
                }).start();
            }
        });

        mcCombo.setOnAction(e -> {
            String mc = mcCombo.getValue();
            if (mc == null) return;
            forgeCombo.getItems().clear();
            forgeCombo.setDisable(true);
            installBtn.setDisable(true);
            status.setText("Loading Forge versions…");
            status.setVisible(true);
            new Thread(() -> {
                try {
                    List<String> fvers = ForgeInstaller.fetchForgeVersions(mc);
                    Platform.runLater(() -> {
                        forgeCombo.setItems(FXCollections.observableArrayList(fvers));
                        if (!fvers.isEmpty()) forgeCombo.getSelectionModel().selectFirst();
                        forgeCombo.setDisable(false);
                        installBtn.setDisable(false);
                        status.setVisible(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> status.setText("Error: " + ex.getMessage()));
                }
            }).start();
        });

        installBtn.setOnAction(e -> {
            String mc    = mcCombo.getValue();
            String forge = forgeCombo.getValue();
            if (mc == null || mc.isBlank()) { alert("", "Select a Minecraft version."); return; }
            if (forge == null || forge.isBlank()) { alert("", "Select a Forge version."); return; }
            installBtn.setDisable(true);
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            bar.setVisible(true);
            status.setVisible(true);
            ForgeInstaller.ProgressCallback fcb = (msg, pct) -> Platform.runLater(() -> {
                status.setText(msg);
                if (pct >= 0) bar.setProgress(pct / 100.0);
            });
            new Thread(() -> {
                try {
                    status.toString();
                    Platform.runLater(() -> status.setText("[Vanilla] Installing base game first…"));
                    MinecraftInstaller.installVersion(mc, (msg, pct) ->
                        Platform.runLater(() -> {
                            status.setText("[Vanilla] " + msg);
                            if (pct >= 0) bar.setProgress(pct / 200.0);
                        }));
                    Platform.runLater(() -> { status.setText("Running Forge installer…"); bar.setProgress(0.5); });
                    String versionId = ForgeInstaller.installForge(forge, fcb);
                    Platform.runLater(() -> {
                        bar.setProgress(1.0);
                        status.setText("Done: " + versionId);
                        installBtn.setDisable(false);
                        activeProfile.setVersion(versionId);
                        pm.upsert(activeProfile);
                        refreshProfileBtn();
                        alert("Installed!", versionId + " is ready!\nClick LAUNCH to play!");
                        navigateTo(slotMain, sideHomeBtn);
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        status.setText("Error: " + ex.getMessage());
                        bar.setProgress(0);
                        installBtn.setDisable(false);
                    });
                }
            }).start();
        });

        return tab;
    }

    private void startFabricInstall() {
        String mc=installMcCombo.getValue(), loader=installLoaderCombo.getValue();
        if(mc==null||mc.isBlank()){alert("","Select a Minecraft version."); return;}
        installStartBtn.setDisable(true); installBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        installBar.setVisible(true); installStatus.setVisible(true);
        FabricInstaller.ProgressCallback fcb=(msg,pct)->Platform.runLater(()->{installStatus.setText(msg);if(pct>=0)installBar.setProgress(pct/100.0);});
        new Thread(()->{try{
            MinecraftInstaller.installVersion(mc,(msg,pct)->Platform.runLater(()->{installStatus.setText("[Vanilla] "+msg);if(pct>=0)installBar.setProgress(pct/200.0);}));
            Platform.runLater(()->{installStatus.setText("Installing Fabric Loader…"); installBar.setProgress(0.5);});
            String fid=(loader!=null&&!loader.isBlank())?FabricInstaller.installFabric(mc,loader,fcb):FabricInstaller.installFabric(mc,fcb);
            Platform.runLater(()->{installBar.setProgress(1.0); installStatus.setText("Done: "+fid); installStartBtn.setDisable(false);
                activeProfile.setVersion(fid); pm.upsert(activeProfile); refreshProfileBtn();
                alert("Installed!",fid+" is ready!"); navigateTo(slotMain, sideHomeBtn);});
        }catch(Exception ex){ex.printStackTrace(); Platform.runLater(()->{installStatus.setText("Error: "+ex.getMessage()); installBar.setProgress(0); installStartBtn.setDisable(false);});}}).start();
    }



    private void buildProfileList() {
        VBox root=new VBox(0); root.setStyle("-fx-background-color:#111111;");

        Label title=new Label("Profiles"); title.setStyle("-fx-text-fill:white;-fx-font-size:20px;-fx-font-weight:bold;");
        Button newBtn =btn("+ New Profile","btn","btn-primary"); hover(newBtn);
        newBtn.setOnAction(e->{ Profile p=Profile.createDefault("New Profile"); pm.upsert(p); buildProfileEdit(p); showPage(slotProfileEdit); });
        BorderPane hdr=new BorderPane(); hdr.setLeft(title); hdr.setRight(newBtn);
        hdr.getStyleClass().add("page-header"); hdr.setPadding(new Insets(18,24,14,24));

        FlowPane cards=new FlowPane(14,14); cards.setPadding(new Insets(20,24,20,24));
        cards.setStyle("-fx-background-color:#111111;");
        for(Profile p:pm.getAll()) cards.getChildren().add(buildProfileCard(p));

        ScrollPane scroll=new ScrollPane(cards); scroll.setFitToWidth(true); scroll.getStyleClass().add("scroll-pane");
        VBox.setVgrow(scroll,Priority.ALWAYS);
        root.getChildren().addAll(hdr,scroll);
        setSlot(slotProfileList, root);
    }

    private VBox buildProfileCard(Profile p) {
        boolean isActive = p.getId().equals(activeProfile.getId());

        VBox card=new VBox(8); card.getStyleClass().add("profile-card");
        card.setStyle("-fx-border-color:"+p.getColor()+";"
            + (isActive ? "-fx-effect:dropshadow(gaussian,"+p.getColor()+",12,0.4,0,0);" : ""));

        Label icon = new Label("●");
        icon.setStyle("-fx-font-size:16px;-fx-text-fill:" + (isActive ? "#1db954" : "#333333") + ";"
            + "-fx-padding:3 4 0 0;");

        Label activeBadge = new Label(isActive ? " ACTIVE" : "");
        activeBadge.setStyle("-fx-text-fill:"+p.getColor()+";-fx-font-size:10px;-fx-font-weight:bold;"
            +"-fx-background-color:"+p.getColor()+"22;-fx-padding:1 5;-fx-background-radius:4;");

        Label name=new Label(p.getName()); name.getStyleClass().add("profile-name");
        HBox nameRow=new HBox(6, icon, name, activeBadge); nameRow.setAlignment(Pos.CENTER_LEFT);

        String rawVer = p.getVersion();
        String typeTag;
        String displayVer;
        if (rawVer.isEmpty()) {
            typeTag    = "";
            displayVer = "No version set";
        } else if (rawVer.startsWith("fabric-loader")) {
            typeTag    = "FABRIC";
            displayVer = rawVer.replaceAll("^fabric-loader-[^-]+-", "");
        } else if (rawVer.contains("forge")) {
            typeTag    = "FORGE";
            displayVer = rawVer;
        } else {
            typeTag    = "VANILLA";
            displayVer = rawVer;
        }

        HBox verRow = new HBox(6);
        verRow.setAlignment(Pos.CENTER_LEFT);
        if (!typeTag.isEmpty()) {
            Label tag = new Label(typeTag);
            tag.setStyle("-fx-text-fill:white;-fx-font-size:9px;-fx-font-weight:bold;"
                +"-fx-background-color:"+p.getColor()+";-fx-padding:1 5;-fx-background-radius:4;");
            verRow.getChildren().add(tag);
        }
        Label ver = new Label(displayVer); ver.getStyleClass().add("profile-version");
        verRow.getChildren().add(ver);

        Label dir=new Label(p.getGameDir().isEmpty()?"Default .minecraft":"Custom dir"); dir.getStyleClass().add("profile-dir");

        Button editBtn=btn("Edit","btn","btn-dark"); hover(editBtn);
        Button modsBtn=btn("Mods","btn","btn-dark"); hover(modsBtn);
        Button delBtn =btn("✕","btn","btn-danger"); hover(delBtn);
        Region sp=new Region(); HBox.setHgrow(sp,Priority.ALWAYS);
        HBox actions=new HBox(6,editBtn,modsBtn,sp,delBtn);

        editBtn.setOnAction(e->{ buildProfileEdit(p); showPage(slotProfileEdit); });
        modsBtn.setOnAction(e->{ buildContentManager(p); showPage(slotContentManager); });
        delBtn.setOnAction(e->{
            if(pm.getAll().size()<=1){alert("Cannot delete","You need at least one profile."); return;}
            pm.delete(p.getId());
            if(activeProfile.getId().equals(p.getId())){
                activeProfile=pm.getAll().get(0); settings.setActiveProfileId(activeProfile.getId());
                settings.save(); refreshProfileBtn();
            }
            buildProfileList(); navigateTo(slotProfileList, sideProfilesBtn);
        });
        card.setOnMouseClicked(ev->{
            if(ev.getPickResult().getIntersectedNode()==editBtn||ev.getPickResult().getIntersectedNode()==modsBtn||ev.getPickResult().getIntersectedNode()==delBtn) return;
            activeProfile=p; settings.setActiveProfileId(p.getId()); settings.save();
            refreshProfileBtn(); refreshProfilePopup(); navigateTo(slotMain, sideHomeBtn);
        });
        card.getChildren().addAll(nameRow, verRow, dir, new Separator(), actions);
        return card;
    }



    private void buildProfileEdit(Profile p) {
        if (p == null) p = Profile.createDefault("Default");
        final Profile prof = p;

        VBox content = new VBox(12);
        content.setPadding(new Insets(24, 32, 32, 32));
        content.setStyle("-fx-background-color:#111111;");

        Label title = new Label("Edit Profile  –  " + prof.getName());
        title.setStyle("-fx-text-fill:white;-fx-font-size:20px;-fx-font-weight:bold;");
        Button backBtn = btn("← Back", "btn", "btn-back"); hover(backBtn);
        backBtn.setOnAction(e -> { buildProfileList(); navigateTo(slotProfileList, sideProfilesBtn); });
        BorderPane hdr = new BorderPane(); hdr.setLeft(title); hdr.setRight(backBtn);

        TextField nameF = field(prof.getName(), "Profile Name");

        String[] COLS = {"#1db954","#4CAF50","#2196F3","#9C27B0","#F44336","#FF9800","#00BCD4","#607D8B"};
        ToggleGroup tg = new ToggleGroup();
        HBox colRow = new HBox(8);
        for (String c : COLS) {
            ToggleButton tb = new ToggleButton(); tb.getStyleClass().add("toggle-button");
            tb.setStyle("-fx-background-color:" + c + ";"); tb.setToggleGroup(tg); tb.setUserData(c);
            if (prof.getColor().equalsIgnoreCase(c)) tb.setSelected(true);
            colRow.getChildren().add(tb);
        }

        ComboBox<String> verCombo = new ComboBox<>();
        verCombo.setEditable(false);
        verCombo.setMaxWidth(Double.MAX_VALUE);
        verCombo.setVisibleRowCount(8);
        limitComboHeight(verCombo, 8);
        verCombo.setPromptText("Select a version…");

        File vd = new File(AppConfig.getMinecraftDir(), "versions");
        if (vd.isDirectory()) {
            File[] vdirs = vd.listFiles(File::isDirectory);
            if (vdirs != null) {
                java.util.Arrays.sort(vdirs, (a, b) -> b.getName().compareTo(a.getName()));
                for (File f2 : vdirs) verCombo.getItems().add(f2.getName());
            }
        }
        if (!prof.getVersion().isEmpty()) verCombo.setValue(prof.getVersion());

        Label verInfo = new Label(); verInfo.setStyle("-fx-text-fill:#555;-fx-font-size:11px;");
        Runnable updateVerInfo = () -> {
            String v = verCombo.getValue() != null ? verCombo.getValue() : "";
            if      (v.startsWith("fabric-loader"))  verInfo.setText("Fabric  •  " + v);
            else if (v.contains("forge"))             verInfo.setText("Forge  •  " + v);
            else if (!v.isEmpty())                    verInfo.setText("Vanilla  •  " + v);
            else                                      verInfo.setText("");
        };
        verCombo.valueProperty().addListener((ob, o, n) -> updateVerInfo.run());
        updateVerInfo.run();

        Button installLink = btn("+ Install new version…", "btn", "btn-dark"); hover(installLink);
        installLink.setStyle("-fx-text-fill:#1db954;-fx-background-color:transparent;-fx-font-size:12px;-fx-padding:2 0;");
        installLink.setOnAction(e -> showPage(slotInstall));

        TextField dirF = field(prof.getGameDir(), "Leave blank to use default .minecraft");
        Button browseDir = btn("Browse…", "btn", "btn-dark"); hover(browseDir);
        browseDir.setOnAction(e -> { DirectoryChooser dc = new DirectoryChooser(); File d = dc.showDialog(null); if (d != null) dirF.setText(d.getAbsolutePath()); });
        HBox dirRow = new HBox(8, dirF, browseDir); HBox.setHgrow(dirF, Priority.ALWAYS);



        TextField modsPathF = field(prof.getModsPath(), "blank = <gameDir>/mods");
        Button browseMods = btn("Browse…", "btn", "btn-dark"); hover(browseMods);
        browseMods.setOnAction(e -> { DirectoryChooser dc = new DirectoryChooser(); File d = dc.showDialog(null); if (d != null) modsPathF.setText(d.getAbsolutePath()); });
        HBox modsRow = new HBox(8, modsPathF, browseMods); HBox.setHgrow(modsPathF, Priority.ALWAYS);

        TextField rpPathF = field(prof.getResourcePacksPath(), "blank = <gameDir>/resourcepacks");
        Button browseRp = btn("Browse…", "btn", "btn-dark"); hover(browseRp);
        browseRp.setOnAction(e -> { DirectoryChooser dc = new DirectoryChooser(); File d = dc.showDialog(null); if (d != null) rpPathF.setText(d.getAbsolutePath()); });
        HBox rpRow = new HBox(8, rpPathF, browseRp); HBox.setHgrow(rpPathF, Priority.ALWAYS);

        TextField shPathF = field(prof.getShadersPath(), "blank = <gameDir>/shaderpacks");
        Button browseSh = btn("Browse…", "btn", "btn-dark"); hover(browseSh);
        browseSh.setOnAction(e -> { DirectoryChooser dc = new DirectoryChooser(); File d = dc.showDialog(null); if (d != null) shPathF.setText(d.getAbsolutePath()); });
        HBox shRow = new HBox(8, shPathF, browseSh); HBox.setHgrow(shPathF, Priority.ALWAYS);

        long maxRam = 8192;
        try { maxRam = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class).getTotalPhysicalMemorySize() / (1024 * 1024); } catch (Exception ignored) {}
        Slider ramSlider = new Slider(512, maxRam, prof.getRamMB());
        ramSlider.setPrefWidth(340); ramSlider.setShowTickLabels(true);
        TextField ramF = field(String.valueOf(prof.getRamMB()), "2048"); ramF.setPrefWidth(70);
        Label mbLbl = new Label("MB"); mbLbl.setStyle("-fx-text-fill:#888;");
        long fMax = maxRam;
        ramF.textProperty().addListener((ob, o, n) -> { if (n.matches("\\d+")) { double v = Double.parseDouble(n); if (v >= 512 && v <= fMax) ramSlider.setValue(v); } });
        ramSlider.valueProperty().addListener((ob, o, n) -> ramF.setText(String.valueOf(n.intValue())));
        HBox ramRow = new HBox(10, ramSlider, ramF, mbLbl); ramRow.setAlignment(Pos.CENTER_LEFT);


        TextArea jvmArea = new TextArea(prof.getJvmArgs());
        jvmArea.setPrefRowCount(3);
        jvmArea.setWrapText(true);
        jvmArea.setPromptText("-XX:+UseG1GC -Xss4M -Dfml.readTimeout=90 …");
        jvmArea.setStyle("-fx-control-inner-background:#1a1a1a;-fx-text-fill:#e0e0e0;-fx-border-color:#2a2a2a;-fx-border-radius:6;-fx-background-radius:6;-fx-font-family:monospace;-fx-font-size:12px;");

        HBox jvmPresets = new HBox(6);
        for (String[] preset : new String[][]{
                {"Clear", ""},
                {"Aikar G1GC", "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"},
                {"ZGC (Java17+)", "-XX:+UseZGC -XX:+ZUncommit -XX:ZUncommitDelay=60"},
                {"Debug", "-Dfml.readTimeout=90 -Dfml.loginTimeout=60 -Xss4M"}
        }) {
            Button pb = btn(preset[0], "btn", "btn-dark");
            pb.setStyle("-fx-font-size:11px;-fx-padding:3 8;");
            final String val = preset[1];
            pb.setOnAction(e -> jvmArea.setText(val));
            hover(pb);
            jvmPresets.getChildren().add(pb);
        }

        TextField resW = field(String.valueOf(prof.getResWidth()), "1280"); resW.setPrefWidth(70);
        TextField resH = field(String.valueOf(prof.getResHeight()), "720"); resH.setPrefWidth(70);
        Label xLbl = new Label("×"); xLbl.setStyle("-fx-text-fill:#888;");
        CheckBox fsBox = new CheckBox("Fullscreen"); fsBox.setSelected(prof.isFullscreen());
        Region rSp = new Region(); HBox.setHgrow(rSp, Priority.ALWAYS);
        HBox resRow = new HBox(8, resW, xLbl, resH, rSp, fsBox); resRow.setAlignment(Pos.CENTER_LEFT);


        ComboBox<String> javaCombo = new ComboBox<>();
        javaCombo.setEditable(false);
        javaCombo.setMaxWidth(Double.MAX_VALUE);
        javaCombo.setVisibleRowCount(6);
        limitComboHeight(javaCombo, 6);
        javaCombo.setPromptText("Auto-detect (recommended)");

        javaCombo.getItems().add("Auto-detect");
        File jvmRoot = new File("/usr/lib/jvm");
        if (jvmRoot.isDirectory()) {
            File[] jvms = jvmRoot.listFiles(f -> f.isDirectory() && new File(f,"bin/java").canExecute());
            if (jvms != null) {
                java.util.Arrays.sort(jvms, (a,b) -> b.getName().compareTo(a.getName()));
                for (File j : jvms) javaCombo.getItems().add(new File(j,"bin/java").getAbsolutePath());
            }
        }

        try {
            Process ap = Runtime.getRuntime().exec(new String[]{"update-alternatives","--list","java"});
            new java.io.BufferedReader(new java.io.InputStreamReader(ap.getInputStream()))
                .lines().map(String::trim).filter(s->!s.isEmpty())
                .forEach(s->{ if(!javaCombo.getItems().contains(s)) javaCombo.getItems().add(s); });
        } catch (Exception ignored) {}

        String savedJava = prof.getJavaPath();
        if (savedJava.isEmpty() || savedJava.equals("Auto-detect")) javaCombo.setValue("Auto-detect");
        else javaCombo.setValue(savedJava);

        Button browseJava = btn("Browse…","btn","btn-dark"); hover(browseJava);
        browseJava.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Select java binary");
            fc.setInitialDirectory(new File("/usr/lib/jvm"));
            File chosen = fc.showOpenDialog(null);
            if (chosen != null) javaCombo.setValue(chosen.getAbsolutePath());
        });
        HBox javaRow = new HBox(8, javaCombo, browseJava); HBox.setHgrow(javaCombo, Priority.ALWAYS);

        Button modsBtn = btn("📦  Manage Mods / ResourcePacks / Shaders", "btn", "btn-dark");
        modsBtn.setMaxWidth(Double.MAX_VALUE); modsBtn.setPrefHeight(40); hover(modsBtn);
        modsBtn.setOnAction(e -> { buildContentManager(prof); showPage(slotContentManager); });

        Button saveBtn = btn("Save", "btn", "btn-primary");
        saveBtn.setPrefWidth(120); saveBtn.setPrefHeight(42); hover(saveBtn);
        saveBtn.setOnAction(e -> {
            prof.setName(nameF.getText().trim().isEmpty() ? "Profile" : nameF.getText().trim());
            if (tg.getSelectedToggle() != null) prof.setColor((String) tg.getSelectedToggle().getUserData());
            prof.setVersion(verCombo.getValue() != null ? verCombo.getValue().trim() : "");
            prof.setGameDir(dirF.getText().trim());
            prof.setModsPath(modsPathF.getText().trim());
            prof.setResourcePacksPath(rpPathF.getText().trim());
            prof.setShadersPath(shPathF.getText().trim());
            try { prof.setRamMB((int) ramSlider.getValue()); } catch (Exception ignored) {}
            prof.setJvmArgs(jvmArea.getText().trim());
            String jv = javaCombo.getValue();
            prof.setJavaPath((jv == null || jv.equals("Auto-detect")) ? "" : jv.trim());
            try { prof.setResWidth(Integer.parseInt(resW.getText())); prof.setResHeight(Integer.parseInt(resH.getText())); } catch (Exception ignored) {}
            prof.setFullscreen(fsBox.isSelected());
            pm.upsert(prof);
            if (prof.getId().equals(activeProfile.getId())) { activeProfile = prof; refreshProfileBtn(); }
            buildProfileList(); navigateTo(slotProfileList, sideProfilesBtn);
        });

        content.getChildren().addAll(
            hdr,
            sLbl("Name"), nameF,
            sLbl("Accent Colour"), colRow,
            sLbl("Minecraft Version"), verCombo, verInfo, installLink,
            sLbl("Game Directory  (blank = default .minecraft)"), dirRow,
            sLbl("Mods Folder  (blank = <gameDir>/mods)"), modsRow,
            sLbl("ResourcePacks Folder  (blank = <gameDir>/resourcepacks)"), rpRow,
            sLbl("Shaders Folder  (blank = <gameDir>/shaderpacks)"), shRow,
            sLbl("RAM Allocation"), ramRow,
            sLbl("JVM Flags"), jvmPresets, jvmArea,
            sLbl("Java Executable  (Auto-detect = use required version)"), javaRow,
            sLbl("Resolution"), resRow,
            new Separator(),
            modsBtn, saveBtn
        );

        ScrollPane sp = new ScrollPane(content); sp.setFitToWidth(true); sp.getStyleClass().add("scroll-pane");
        setSlot(slotProfileEdit, sp);
    }



    private void buildContentBrowser() {
        VBox root = new VBox(0);
        root.setStyle("-fx-background-color:#111111;");
        Label title = new Label("Get Content");
        title.setStyle("-fx-text-fill:white;-fx-font-size:20px;-fx-font-weight:bold;");
        BorderPane hdr = new BorderPane();
        hdr.setLeft(title);
        hdr.getStyleClass().add("page-header");
        hdr.setPadding(new Insets(18, 24, 14, 24));
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            contentTab("Mods",           ModrinthClient.ProjectType.MOD),
            contentTab("Resource Packs", ModrinthClient.ProjectType.RESOURCEPACK),
            contentTab("Shaders",        ModrinthClient.ProjectType.SHADER),
            contentTab("Modpacks",       ModrinthClient.ProjectType.MODPACK)
        );
        VBox.setVgrow(tabs, Priority.ALWAYS);
        root.getChildren().addAll(hdr, tabs);
        setSlot(slotContentBrowser, root);
    }

    private Tab contentTab(String label, ModrinthClient.ProjectType type) {
        Tab tab = new Tab(label);

        VBox box = new VBox(0);
        box.setStyle("-fx-background-color:#111111;");

        // ── Filter row ────────────────────────────────────────────────────────
        HBox filterRow = new HBox(10);
        filterRow.setPadding(new Insets(14, 18, 10, 18));
        filterRow.setAlignment(Pos.CENTER_LEFT);

        TextField searchField = new TextField();
        searchField.setPromptText("Search " + label + "…");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        ComboBox<String> mcVerCombo = new ComboBox<>();
        mcVerCombo.setPromptText("MC Version");
        mcVerCombo.setPrefWidth(130);
        mcVerCombo.setVisibleRowCount(8);
        limitComboHeight(mcVerCombo, 8);

        Button searchBtn = btn("Search", "btn", "btn-primary");
        searchBtn.setPrefHeight(36);
        hover(searchBtn);

        filterRow.getChildren().addAll(searchField, mcVerCombo, searchBtn);

        // ── Status label ──────────────────────────────────────────────────────
        Label statusLbl = new Label("");
        statusLbl.setStyle("-fx-text-fill:#666;-fx-font-size:12px;-fx-font-family:'Ubuntu';-fx-padding:0 18 4 18;");

        // ── Result list ───────────────────────────────────────────────────────
        ListView<ModrinthClient.ModrinthProject> resultList = new ListView<>();
        resultList.getStyleClass().add("list-view");
        resultList.setStyle("-fx-background-color:#111111;-fx-border-color:transparent;");
        resultList.setFixedCellSize(140);
        VBox.setVgrow(resultList, Priority.ALWAYS);

        resultList.setCellFactory(lv -> new ListCell<>() {
            private final javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView();
            private final StackPane iconPane;
            private final Label nameL  = new Label();
            private final Label descL  = new Label();
            private final Label dlL    = new Label();
            private final Button installBtn;
            private final HBox row;

            {
                // ── Icon 96×96 rounded ────────────────────────────────────────
                iconView.setFitWidth(96); iconView.setFitHeight(96);
                iconView.setPreserveRatio(false);
                javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(96, 96);
                clip.setArcWidth(18); clip.setArcHeight(18);
                iconView.setClip(clip);

                iconPane = new StackPane(iconView);
                iconPane.setMinSize(96, 96); iconPane.setMaxSize(96, 96);
                iconPane.setStyle("-fx-background-color:#1e1e1e;-fx-background-radius:18;");

                // ── Text ──────────────────────────────────────────────────────
                nameL.setStyle(
                    "-fx-text-fill:#ffffff;-fx-font-size:16px;" +
                    "-fx-font-weight:bold;-fx-font-family:'Ubuntu';"
                );
                descL.setStyle(
                    "-fx-text-fill:#777777;-fx-font-size:13px;-fx-font-family:'Ubuntu';"
                );
                descL.setMaxWidth(520); descL.setWrapText(true);
                dlL.setStyle(
                    "-fx-text-fill:#1db954;-fx-font-size:13px;-fx-font-family:'Ubuntu';"
                );

                VBox infoBox = new VBox(6, nameL, descL, dlL);
                infoBox.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(infoBox, Priority.ALWAYS);

                // ── Install button ────────────────────────────────────────────
                installBtn = new Button("Install");
                installBtn.setPrefHeight(42); installBtn.setPrefWidth(110);
                installBtn.setStyle(
                    "-fx-background-color:#1db954;-fx-text-fill:white;" +
                    "-fx-font-weight:bold;-fx-font-size:14px;-fx-font-family:'Ubuntu';" +
                    "-fx-background-radius:10;-fx-cursor:hand;"
                );

                row = new HBox(20, iconPane, infoBox, installBtn);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(16, 22, 16, 22));
                row.setStyle(
                    "-fx-background-color:#181818;" +
                    "-fx-background-radius:12;" +
                    "-fx-border-color:#232323;" +
                    "-fx-border-radius:12;" +
                    "-fx-border-width:1;"
                );
                row.setOnMouseEntered(e -> row.setStyle(
                    "-fx-background-color:#1e1e1e;" +
                    "-fx-background-radius:12;" +
                    "-fx-border-color:#2e2e2e;" +
                    "-fx-border-radius:12;" +
                    "-fx-border-width:1;"
                ));
                row.setOnMouseExited(e -> row.setStyle(
                    "-fx-background-color:#181818;" +
                    "-fx-background-radius:12;" +
                    "-fx-border-color:#232323;" +
                    "-fx-border-radius:12;" +
                    "-fx-border-width:1;"
                ));
            }

            @Override
            protected void updateItem(ModrinthClient.ModrinthProject item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                nameL.setText(item.title);
                descL.setText(item.description);
                dlL.setText("⬇  " + formatDownloads(item.downloads));
                loadIconAsync(iconView, item.iconUrl, item.title);
                installBtn.setOnAction(e -> handleInstall(item, mcVerCombo.getValue(), type, installBtn));
                setGraphic(row);
                setText(null);
                setStyle("-fx-background-color:transparent;-fx-padding:5 18;");
            }
        });

        // ── Pagination bar ────────────────────────────────────────────────────
        final int PAGE_SIZE = 25;
        final int[] currentPage = {1};
        final int[] totalPages  = {1};
        final String[] lastQuery = {""};

        Button prevBtn = new Button("‹");
        Button nextBtn = new Button("›");
        Label pageInfo = new Label("1");

        String pageBtnStyle =
            "-fx-background-color:#1e1e1e;-fx-text-fill:#cccccc;" +
            "-fx-font-size:16px;-fx-font-family:'Ubuntu';" +
            "-fx-background-radius:8;-fx-pref-width:36;-fx-pref-height:36;-fx-cursor:hand;";
        prevBtn.setStyle(pageBtnStyle);
        nextBtn.setStyle(pageBtnStyle);
        pageInfo.setStyle("-fx-text-fill:#1db954;-fx-font-size:13px;-fx-font-family:'Ubuntu';-fx-font-weight:bold;");

        HBox pagination = new HBox(8, prevBtn, pageInfo, nextBtn);
        pagination.setAlignment(Pos.CENTER);
        pagination.setPadding(new Insets(10, 0, 12, 0));
        pagination.setVisible(false);

        Label emptyLbl = new Label("Search for " + label.toLowerCase() + " above.");
        emptyLbl.setStyle("-fx-text-fill:#444;-fx-font-size:14px;-fx-font-family:'Ubuntu';");
        StackPane center = new StackPane(resultList, emptyLbl);
        VBox.setVgrow(center, Priority.ALWAYS);
        resultList.setVisible(false);

        box.getChildren().addAll(filterRow, statusLbl, center, pagination);
        tab.setContent(box);

        AtomicBoolean searching = new AtomicBoolean(false);

        // ── Load a specific page ──────────────────────────────────────────────
        Runnable[] loadPage = {null};
        loadPage[0] = () -> {
            if (searching.get()) return;
            searching.set(true);
            searchBtn.setDisable(true);
            emptyLbl.setVisible(false);
            resultList.setVisible(false);
            int offset = (currentPage[0] - 1) * PAGE_SIZE;
            String q = lastQuery[0];
            String mcVer = mcVerCombo.getValue();

            statusLbl.setText("Loading page " + currentPage[0] + "…");
            new Thread(() -> {
                try {
                    List<ModrinthClient.ModrinthProject> results = q.isEmpty()
                        ? ModrinthClient.searchPage(type, mcVer, offset, PAGE_SIZE)
                        : ModrinthClient.searchPage(type, mcVer, q, offset, PAGE_SIZE);
                    // Estimate total pages from offset
                    Platform.runLater(() -> {
                        resultList.setItems(FXCollections.observableArrayList(results));
                        resultList.setVisible(true);
                        resultList.scrollTo(0);
                        // Update pagination
                        if (results.size() == PAGE_SIZE) totalPages[0] = Math.max(totalPages[0], currentPage[0] + 1);
                        pageInfo.setText(currentPage[0] + " / " + totalPages[0] + "+");
                        prevBtn.setDisable(currentPage[0] <= 1);
                        nextBtn.setDisable(results.size() < PAGE_SIZE);
                        pagination.setVisible(true);
                        statusLbl.setText((q.isEmpty() ? "Popular " : q + " — ") + results.size() + " results");
                        searching.set(false);
                        searchBtn.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        statusLbl.setText("Error: " + ex.getMessage());
                        searching.set(false);
                        searchBtn.setDisable(false);
                    });
                }
            }).start();
        };

        prevBtn.setOnAction(e -> { if (currentPage[0] > 1) { currentPage[0]--; loadPage[0].run(); } });
        nextBtn.setOnAction(e -> { currentPage[0]++; loadPage[0].run(); });

        // ── Auto-load on tab open ─────────────────────────────────────────────
        tab.selectedProperty().addListener((ob, o, n) -> {
            if (!n) return;
            if (mcVerCombo.getItems().isEmpty()) {
                new Thread(() -> {
                    try {
                        List<String> vers = MinecraftInstaller.fetchVersionsByType("release");
                        Platform.runLater(() -> {
                            mcVerCombo.setItems(FXCollections.observableArrayList(vers));
                            if (!vers.isEmpty()) mcVerCombo.getSelectionModel().selectFirst();
                        });
                    } catch (Exception ignored) {}
                }).start();
            }
            if (resultList.getItems().isEmpty() && !searching.get()) {
                currentPage[0] = 1; lastQuery[0] = "";
                loadPage[0].run();
            }
        });

        // ── Search ────────────────────────────────────────────────────────────
        Runnable doSearch = () -> {
            currentPage[0] = 1;
            totalPages[0]  = 1;
            lastQuery[0]   = searchField.getText().trim();
            loadPage[0].run();
        };
        searchBtn.setOnAction(e -> doSearch.run());
        searchField.setOnAction(e -> doSearch.run());

        return tab;
    }

    private void handleInstall(ModrinthClient.ModrinthProject project,
                               String mcVersion,
                               ModrinthClient.ProjectType type,
                               Button installBtn) {
        installBtn.setDisable(true);
        installBtn.setText("…");

        new Thread(() -> {
            try {
                List<ModrinthClient.ModrinthVersion> versions =
                    ModrinthClient.getVersions(project.projectId, mcVersion);

                if (versions.isEmpty()) {
                    Platform.runLater(() -> {
                        alert("No files", "No compatible files found for this version.");
                        installBtn.setDisable(false);
                        installBtn.setText("Install");
                    });
                    return;
                }

                Platform.runLater(() -> {
                    ChoiceDialog<ModrinthClient.ModrinthVersion> dlg =
                        new ChoiceDialog<>(versions.get(0), versions);
                    dlg.setTitle("Choose Version");
                    dlg.setHeaderText(project.title);
                    dlg.setContentText("Version:");
                    dlg.showAndWait().ifPresentOrElse(
                        chosen -> downloadContent(chosen, type, project, installBtn),
                        () -> {
                            installBtn.setDisable(false);
                            installBtn.setText("Install");
                        }
                    );
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    alert("Error", ex.getMessage());
                    installBtn.setDisable(false);
                    installBtn.setText("Install");
                });
            }
        }).start();
    }

    private void downloadContent(ModrinthClient.ModrinthVersion version,
                                 ModrinthClient.ProjectType type,
                                 ModrinthClient.ModrinthProject project,
                                 Button installBtn) {
        String targetDir = switch (type) {
            case MOD          -> activeProfile.modsDir();
            case RESOURCEPACK -> activeProfile.resourcePacksDir();
            case SHADER       -> activeProfile.shadersDir();
            case MODPACK      -> activeProfile.effectiveGameDir() + java.io.File.separator + "modpacks";
        };

        installBtn.setText("↓");
        new Thread(() -> {
            try {
                java.io.File dest = ModrinthClient.download(version, targetDir,
                    (msg, pct) -> Platform.runLater(() -> installBtn.setText(pct + "%")));

                if (type == ModrinthClient.ProjectType.MODPACK) {
                    Platform.runLater(() -> {
                        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
                        ask.setTitle("Create Profile?");
                        ask.setHeaderText("Create a new profile for " + "  project.title  " + "?");
                        ask.setContentText("A profile will be set up using this modpack's folder.");
                        ask.showAndWait().ifPresent(btn -> {
                            if (btn == ButtonType.OK) {
                                Profile p = Profile.createDefault(project.title);
                                p.setGameDir(dest.getParent());
                                p.setModsPath(activeProfile.modsDir());
                                pm.upsert(p);
                                activeProfile = p;
                                settings.setActiveProfileId(p.getId());
                                settings.save();
                                refreshProfileBtn();
                                refreshProfilePopup();
                                alert("Done!", "Profile " + "  p.getName()  " + " created.");
                            }
                        });
                    });
                } else {
                    Platform.runLater(() -> {
                        alert("Installed!", version.filename + " saved to:\n" + targetDir);
                    });
                }

                Platform.runLater(() -> {
                    installBtn.setDisable(false);
                    installBtn.setText("✓ Done");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    alert("Download failed", ex.getMessage());
                    installBtn.setDisable(false);
                    installBtn.setText("Install");
                });
            }
        }).start();
    }

    /**
     * Loads an image from a URL asynchronously and sets it on the given ImageView.
     * Uses a dedicated thread with proper HTTP headers to avoid Modrinth CDN blocks.
     */
    private static final java.util.concurrent.ExecutorService ICON_POOL =
        java.util.concurrent.Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "icon-loader");
            t.setDaemon(true);
            return t;
        });

    // Cache: URL → Image (so same icon isn't downloaded twice)
    private static final java.util.concurrent.ConcurrentHashMap<String, javafx.scene.image.Image>
        ICON_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static void loadIconAsync(javafx.scene.image.ImageView iv, String url, String fallbackLetter) {
        setLetterPlaceholder(iv, fallbackLetter);
        if (url == null || url.isEmpty()) return;

        // Tag this view with the URL it wants — used to prevent stale callbacks
        iv.setUserData(url);

        // Already cached?
        javafx.scene.image.Image cached = ICON_CACHE.get(url);
        if (cached != null) { iv.setImage(cached); return; }

        ICON_POOL.submit(() -> {
            try {
                java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "OnyxLauncher/1.0 (contact@example.com)");
                conn.setConnectTimeout(12_000);
                conn.setReadTimeout(20_000);
                conn.connect();
                if (conn.getResponseCode() != 200) return;

                byte[] bytes;
                try (java.io.InputStream is = conn.getInputStream()) {
                    bytes = is.readAllBytes();
                }

                // Use ImageIO (supports webp via TwelveMonkeys) → convert to JavaFX Image
                java.awt.image.BufferedImage awtImg = ImageIO.read(
                    new java.io.ByteArrayInputStream(bytes));
                if (awtImg == null) return;

                // Resize to 128×128 (high-res for 96px display — stays sharp)
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(128, 128,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.drawImage(awtImg, 0, 0, 128, 128, null);
                g2.dispose();

                // Convert AWT → JavaFX Image via PNG bytes
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                ImageIO.write(scaled, "png", baos);
                javafx.scene.image.Image fxImg = new javafx.scene.image.Image(
                    new java.io.ByteArrayInputStream(baos.toByteArray()));

                if (!fxImg.isError()) {
                    ICON_CACHE.put(url, fxImg);
                    Platform.runLater(() -> {
                        if (url.equals(iv.getUserData())) iv.setImage(fxImg);
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private static void setLetterPlaceholder(javafx.scene.image.ImageView iv, String letter) {
        javafx.scene.canvas.Canvas c = new javafx.scene.canvas.Canvas(128, 128);
        javafx.scene.canvas.GraphicsContext gc = c.getGraphicsContext2D();
        gc.setFill(javafx.scene.paint.Color.web("#252525"));
        gc.fillRoundRect(0, 0, 128, 128, 24, 24);
        gc.setFill(javafx.scene.paint.Color.web("#555555"));
        gc.setFont(javafx.scene.text.Font.font("Ubuntu", javafx.scene.text.FontWeight.BOLD, 52));
        String l = (letter == null || letter.isEmpty()) ? "?" : String.valueOf(letter.charAt(0)).toUpperCase();
        gc.fillText(l, 42, 86);
        javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
        sp.setFill(javafx.scene.paint.Color.TRANSPARENT);
        iv.setImage(c.snapshot(sp, null));
    }

    private static String formatDownloads(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private void buildContentManager(Profile profile) {
        if(profile==null) profile=activeProfile;
        final Profile prof = profile;

        VBox root=new VBox(0); root.setStyle("-fx-background-color:#111111;");
        Label title=new Label("Content Manager  –  "+prof.getName());
        title.setStyle("-fx-text-fill:white;-fx-font-size:20px;-fx-font-weight:bold;");
        Button backBtn=btn("← Back","btn","btn-back"); hover(backBtn);
        backBtn.setOnAction(e->{ buildProfileEdit(prof); showPage(slotProfileEdit); });
        BorderPane hdr=new BorderPane(); hdr.setLeft(title); hdr.setRight(backBtn);
        hdr.getStyleClass().add("page-header"); hdr.setPadding(new Insets(18,24,14,24));

        TabPane tabs=new TabPane(); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        String rawVer=prof.getVersion()
            .replaceAll("^fabric-loader-[^-]+-","")
            .replaceAll("^forge-.*-","");
        final String mcVer=rawVer.isEmpty()?null:rawVer;

        tabs.getTabs().addAll(
            contentTab("Mods",           ModrinthClient.ProjectType.MOD,          prof.modsDir(),            mcVer),
            contentTab("Resource Packs", ModrinthClient.ProjectType.RESOURCEPACK, prof.resourcePacksDir(),   mcVer),
            contentTab("Shaders",        ModrinthClient.ProjectType.SHADER,       prof.shadersDir(),         mcVer)
        );
        VBox.setVgrow(tabs,Priority.ALWAYS);
        root.getChildren().addAll(hdr,tabs);
        setSlot(slotContentManager, root);
    }

    private Tab contentTab(String label, ModrinthClient.ProjectType type,
                           String targetDir, String mcVer) {
        Tab tab=new Tab(label);
        BorderPane pane=new BorderPane(); pane.setStyle("-fx-background-color:#111111;");

        VBox left=new VBox(8); left.setPrefWidth(240); left.setPadding(new Insets(14,10,14,14));
        left.setStyle("-fx-background-color:#161616;");
        Label instHdr=new Label("Installed"); instHdr.getStyleClass().add("label-section");
        ListView<String> instList=new ListView<>(); instList.getStyleClass().add("list-view"); VBox.setVgrow(instList,Priority.ALWAYS);
        refreshInstalled(instList,targetDir);
        Button openBtn=btn("📁  Open Folder","btn","btn-dark"); openBtn.setMaxWidth(Double.MAX_VALUE); hover(openBtn);
        openBtn.setOnAction(e->{try{java.awt.Desktop.getDesktop().open(new File(targetDir));}catch(Exception ex){ex.printStackTrace();}});
        Button removeBtn=btn("🗑  Remove","btn","btn-danger"); removeBtn.setMaxWidth(Double.MAX_VALUE); hover(removeBtn);
        removeBtn.setOnAction(e->{String s=instList.getSelectionModel().getSelectedItem(); if(s!=null){new File(targetDir,s).delete(); refreshInstalled(instList,targetDir);}});
        left.getChildren().addAll(instHdr,instList,openBtn,removeBtn);

        VBox right=new VBox(10); right.setPadding(new Insets(14,16,14,10)); right.setStyle("-fx-background-color:#111111;");
        TextField searchF=new TextField(); searchF.setPromptText("Search Modrinth for "+label+"…"); HBox.setHgrow(searchF,Priority.ALWAYS);
        Button searchBtn=btn("Search","btn","btn-primary"); hover(searchBtn);
        HBox searchRow=new HBox(8,searchF,searchBtn); searchRow.setAlignment(Pos.CENTER_LEFT);

        ListView<ModrinthClient.ModrinthProject> results=new ListView<>(); results.getStyleClass().add("list-view"); VBox.setVgrow(results,Priority.ALWAYS);
        results.setCellFactory(lv->new ListCell<>(){
            @Override protected void updateItem(ModrinthClient.ModrinthProject item, boolean empty){
                super.updateItem(item,empty); if(empty||item==null){setGraphic(null);setText(null);return;}
                VBox box=new VBox(3);
                Label t=new Label(item.title); t.setStyle("-fx-text-fill:white;-fx-font-size:13px;-fx-font-weight:bold;");
                Label d=new Label(item.description); d.setStyle("-fx-text-fill:#666;-fx-font-size:11px;"); d.setWrapText(true);
                Label dl=new Label("⬇ "+item.downloads); dl.setStyle("-fx-text-fill:#444;-fx-font-size:10px;");
                box.getChildren().addAll(t,d,dl); setGraphic(box); setText(null); setStyle("-fx-background-color:transparent;");
            }
        });

        ProgressBar dlBar=new ProgressBar(0); dlBar.setPrefWidth(Double.MAX_VALUE); dlBar.setVisible(false); dlBar.getStyleClass().add("progress-bar");
        Label dlSt=new Label(); dlSt.getStyleClass().add("label-hint"); dlSt.setVisible(false);

        Button installBtn=btn("⬇  Install Selected","btn","btn-primary");
        installBtn.setMaxWidth(Double.MAX_VALUE); installBtn.setPrefHeight(40); installBtn.setDisable(true); hover(installBtn);
        results.getSelectionModel().selectedItemProperty().addListener((ob,o,n)->installBtn.setDisable(n==null));

        Runnable doSearch=()->{
            String q=searchF.getText().trim(); if(q.isEmpty())return;
            searchBtn.setDisable(true); results.getItems().clear(); dlSt.setText("Searching…"); dlSt.setVisible(true);
            new Thread(()->{try{
                List<ModrinthClient.ModrinthProject> res=ModrinthClient.search(q,type,mcVer,30);
                Platform.runLater(()->{results.setItems(FXCollections.observableArrayList(res)); searchBtn.setDisable(false); dlSt.setVisible(false);});
            }catch(Exception ex){Platform.runLater(()->{searchBtn.setDisable(false); dlSt.setText("Search failed: "+ex.getMessage());});}}).start();
        };
        searchBtn.setOnAction(e->doSearch.run()); searchF.setOnAction(e->doSearch.run());

        installBtn.setOnAction(e->{
            ModrinthClient.ModrinthProject sel=results.getSelectionModel().getSelectedItem(); if(sel==null)return;
            installBtn.setDisable(true); dlBar.setVisible(true); dlSt.setVisible(true);
            dlBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            new Thread(()->{try{
                List<ModrinthClient.ModrinthVersion> vers=ModrinthClient.getVersions(sel.projectId,mcVer);
                if(vers.isEmpty()){Platform.runLater(()->{dlSt.setText("No compatible version found."); installBtn.setDisable(false); dlBar.setVisible(false);}); return;}
                ModrinthClient.ModrinthVersion ver=vers.get(0);
                ModrinthClient.ProgressCallback cb=(msg,pct)->Platform.runLater(()->{dlSt.setText(msg);if(pct>=0)dlBar.setProgress(pct/100.0);});
                ModrinthClient.download(ver,targetDir,cb);
                Platform.runLater(()->{dlBar.setProgress(1.0); dlSt.setText("✓ "+ver.filename); installBtn.setDisable(false); refreshInstalled(instList,targetDir);});
            }catch(Exception ex){ex.printStackTrace(); Platform.runLater(()->{dlSt.setText("Error: "+ex.getMessage()); installBtn.setDisable(false); dlBar.setProgress(0);});}}).start();
        });

        right.getChildren().addAll(searchRow,results,dlBar,dlSt,installBtn);
        pane.setLeft(left); pane.setCenter(right);
        tab.setContent(pane);
        return tab;
    }

    private void refreshInstalled(ListView<String> lv, String dir) {
        lv.getItems().clear();
        File d=new File(dir); if(!d.isDirectory())return;
        File[] fs=d.listFiles(f->!f.isDirectory());
        if(fs!=null) for(File f:fs) lv.getItems().add(f.getName());
    }



    private void hideToTray(Stage stage) {
        if(!SystemTray.isSupported()){stage.setIconified(true);return;}
        stage.hide();
        try{
            if(trayIcon==null){
                BufferedImage img=ImageIO.read(getClass().getResource("/icon.png"));
                java.awt.Image sc=img.getScaledInstance(16,16,java.awt.Image.SCALE_SMOOTH);
                PopupMenu menu=new PopupMenu();
                MenuItem open=new MenuItem("Open"); open.addActionListener(e->Platform.runLater(()->restoreFromTray(stage)));
                MenuItem exit=new MenuItem("Exit"); exit.addActionListener(e->{SystemTray.getSystemTray().remove(trayIcon);System.exit(0);});
                menu.add(open); menu.addSeparator(); menu.add(exit);
                trayIcon=new TrayIcon(sc,"OnyxLauncher",menu); trayIcon.setImageAutoSize(true);
                trayIcon.addActionListener(e->Platform.runLater(()->restoreFromTray(stage)));
            }
            SystemTray.getSystemTray().add(trayIcon);
        }catch(Exception ignored){}
    }
    private void restoreFromTray(Stage stage){
        if(SystemTray.isSupported()&&trayIcon!=null) SystemTray.getSystemTray().remove(trayIcon);
        stage.show(); stage.toFront();
    }



    private VBox popupCard() {
        VBox v=new VBox(2); v.getStyleClass().add("popup-card");
        v.setVisible(false); v.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);
        return v;
    }
    private Button btn(String text, String... css) {
        Button b=new Button(text); b.getStyleClass().addAll(css); return b;
    }

    private static javafx.scene.Node svgIcon(String... pathData) {
        javafx.scene.Group group = new javafx.scene.Group();
        for (String d : pathData) {
            javafx.scene.shape.SVGPath p = new javafx.scene.shape.SVGPath();
            p.setContent(d);
            p.setStroke(javafx.scene.paint.Color.web("#888888"));
            p.setFill(javafx.scene.paint.Color.TRANSPARENT);
            p.setStrokeWidth(1.8);
            p.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            p.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
            group.getChildren().add(p);
        }

        group.setScaleX(20.0 / 24.0);
        group.setScaleY(20.0 / 24.0);
        group.setMouseTransparent(true);
        return group;
    }

    private static final String[] ICON_HOME = {
        "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
        "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
    };
    private static final String[] ICON_PROFILES = {
        "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
        "M16 3.128a4 4 0 0 1 0 7.744",
        "M22 21v-2a4 4 0 0 0-3-3.87",
        "M9 7 m-4 0 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0"
    };
    private static final String[] ICON_INSTALL = {
        "M12 15V3",
        "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
        "m7 10 5 5 5-5"
    };

    private static final String[] ICON_SETTINGS = {
        "M14 17H5",
        "M19 7h-9",
        "M17 14 m-3 0 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        "M7 4 m-3 0 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0"
    };

    private static final String[] ICON_TERMINAL = {
        "M4 17l6-6-6-6",
        "M12 19h8"
    };
    private static final String[] ICON_CONTENT = {
        "M20 7H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2z",
        "M12 12h.01",
        "M8 12h.01",
        "M16 12h.01"
    };

    private Button sideNavBtn(String[] iconPaths, String label, boolean active) {
        javafx.scene.Node iconNode = svgIcon(iconPaths);
        StackPane iconBox = new StackPane(iconNode);
        iconBox.setMinSize(24, 24);
        iconBox.setMaxSize(24, 24);
        iconBox.setMouseTransparent(true);

        Label textLbl = new Label(label);
        textLbl.getStyleClass().add("sidebar-btn-text");

        HBox inner = new HBox(12, iconBox, textLbl);
        inner.setAlignment(Pos.CENTER_LEFT);
        inner.setMouseTransparent(true);

        Button b = new Button();
        b.setGraphic(inner);
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("sidebar-btn");
        if (active) b.getStyleClass().add("sidebar-btn-active");

        b.setOnMousePressed(e -> {
            javafx.animation.ScaleTransition press =
                new javafx.animation.ScaleTransition(Duration.millis(80), b);
            press.setToX(0.95); press.setToY(0.95);
            press.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
            press.play();
        });
        b.setOnMouseReleased(e -> {
            javafx.animation.ScaleTransition release =
                new javafx.animation.ScaleTransition(Duration.millis(120), b);
            release.setToX(1.0); release.setToY(1.0);
            release.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
            release.play();
        });

        return b;
    }

    private static void tintSvgGroup(javafx.scene.Node node, String color) {
        javafx.scene.paint.Color c = javafx.scene.paint.Color.web(color);
        if (node instanceof javafx.scene.Group g) {
            for (javafx.scene.Node child : g.getChildren()) {
                if (child instanceof javafx.scene.shape.SVGPath sp) sp.setStroke(c);
            }
        }
    }

    private static javafx.scene.Node svgGroupOf(Button btn) {
        if (btn.getGraphic() instanceof HBox hb && !hb.getChildren().isEmpty()
                && hb.getChildren().get(0) instanceof StackPane sp
                && !sp.getChildren().isEmpty()) {
            return sp.getChildren().get(0);
        }
        return null;
    }

    private void setSideActive(Button btn) {
        if (activeSideBtn != null) {
            activeSideBtn.getStyleClass().remove("sidebar-btn-active");
            tintSvgGroup(svgGroupOf(activeSideBtn), "#888888");
        }
        btn.getStyleClass().add("sidebar-btn-active");
        tintSvgGroup(svgGroupOf(btn), "#1db954");
        activeSideBtn = btn;
    }
    private Button popupItem(String text, String color) {
        Button b=new Button(text); b.setMaxWidth(Double.MAX_VALUE);
        b.setMnemonicParsing(false);
        b.getStyleClass().add("popup-item");
        b.setStyle("-fx-text-fill:"+color+";"); return b;
    }
    private Label sLbl(String text) {
        Label l=new Label(text); l.getStyleClass().add("label-section"); return l;
    }
    private TextField field(String val, String prompt) {
        TextField f=new TextField(val); f.setPromptText(prompt); return f;
    }
    private void alert(String title, String msg) {
        Alert a=new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title); a.setHeaderText(null); a.setContentText(msg); a.showAndWait();
    }

    private void loadFont(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) javafx.scene.text.Font.loadFont(is, 13);
        } catch (Exception e) {
            System.err.println("Font load failed: " + resourcePath + " – " + e.getMessage());
        }
    }

    private static void limitComboHeight(ComboBox<?> combo, int maxRows) {
        combo.setVisibleRowCount(maxRows);
        combo.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {

                javafx.scene.control.skin.ComboBoxListViewSkin<?> skin =
                    (javafx.scene.control.skin.ComboBoxListViewSkin<?>) combo.getSkin();
                if (skin != null) {
                    javafx.scene.Node popup = skin.getPopupContent();
                    if (popup instanceof javafx.scene.control.ListView<?> lv) {
                        double rowH = 30;
                        lv.setMaxHeight(maxRows * rowH);
                        lv.setPrefHeight(Math.min(combo.getItems().size(), maxRows) * rowH);
                    }
                }
            }
        });
    }

    private static void applyGpuCardSelected(VBox card, String color) {
        card.setStyle("-fx-background-color:#1a1a1a;-fx-background-radius:8;"
            + "-fx-border-radius:8;-fx-border-width:2;"
            + "-fx-border-color:" + color + ";-fx-cursor:hand;");
    }

    public static void main(String[] args) { launch(args); }
}
