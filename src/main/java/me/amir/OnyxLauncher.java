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
import java.util.List;

/**
 * OnyxLauncher – main UI class.
 *
 * Navigation model
 * ─────────────────
 * rootStack contains FIXED slot containers (StackPane wrappers).
 * Rebuilding a page calls slot.getChildren().setAll(newContent) so the
 * container reference in rootStack never changes – only its contents do.
 * showPage(slot) just toggles visibility on those containers.
 */
public class OnyxLauncher extends Application {

    // ── state ─────────────────────────────────────────────────────────────────
    private Settings       settings;
    private ProfileManager pm;
    private Profile        activeProfile;

    // ── root ──────────────────────────────────────────────────────────────────
    private StackPane  rootStack;

    // ── fixed page SLOTS (never replaced, only their children change) ─────────
    private final StackPane slotMain           = slot();
    private final StackPane slotSettings       = slot();
    private final StackPane slotInstall        = slot();
    private final StackPane slotProfileList    = slot();
    private final StackPane slotProfileEdit    = slot();
    private final StackPane slotContentManager = slot();
    private final StackPane slotOverlay        = slot();

    // ── floating popup layer ──────────────────────────────────────────────────
    private AnchorPane popupLayer;
    private VBox       accountPopup;
    private VBox       profilePopup;

    // ── live bottom-bar refs (updated when active profile changes) ────────────
    private Button profileBtn;
    private Button usernameBtn;

    // ── install page widgets (fields so background threads reach them) ────────
    private TabPane          installTabPane;
    private ComboBox<String> installMcCombo;
    private ComboBox<String> installLoaderCombo;
    private ProgressBar      installBar;
    private Label            installStatus;
    private Button           installStartBtn;

    // ── log ───────────────────────────────────────────────────────────────────
    private TextArea           debugArea;
    private final StringBuilder logBuf = new StringBuilder();

    private TrayIcon trayIcon;
    private boolean  isGameRunning = false;

    // =========================================================================
    // start
    // =========================================================================
    @Override
    public void start(Stage stage) {
        settings      = Settings.load();
        pm            = ProfileManager.get();
        activeProfile = pm.getById(settings.getActiveProfileId());

        try { stage.getIcons().add(new Image(getClass().getResourceAsStream("/icon.png"))); }
        catch (Exception ignored) {}
        Platform.setImplicitExit(false);

        // build popup layer
        accountPopup = popupCard();
        profilePopup = popupCard();
        popupLayer   = new AnchorPane(accountPopup, profilePopup);
        popupLayer.setPickOnBounds(false);

        // build all pages into their slots
        buildMain(stage);
        buildSettings(stage);
        buildInstall();
        buildProfileList();
        buildProfileEdit(null);
        buildContentManager(null);
        buildOverlay();

        // assemble root
        rootStack = new StackPane(
            slotMain, slotSettings, slotInstall,
            slotProfileList, slotProfileEdit, slotContentManager,
            slotOverlay, popupLayer
        );
        rootStack.setStyle("-fx-background-color:#111111;");

        showPage(slotMain);

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

    // =========================================================================
    // Navigation  – only touches visibility, never adds/removes from rootStack
    // =========================================================================
    private void showPage(StackPane slot) {
        slotMain.setVisible(false);
        slotSettings.setVisible(false);
        slotInstall.setVisible(false);
        slotProfileList.setVisible(false);
        slotProfileEdit.setVisible(false);
        slotContentManager.setVisible(false);
        slotOverlay.setVisible(false);
        // popupLayer always visible, overlayView handled separately
        slot.setVisible(true);
        accountPopup.setVisible(false);
        profilePopup.setVisible(false);
    }

    /** Creates an empty transparent StackPane used as a page slot. */
    private static StackPane slot() {
        StackPane sp = new StackPane();
        sp.setVisible(false);
        return sp;
    }

    /** Replaces the content of a slot (keeps the slot node itself stable). */
    private static void setSlot(StackPane slot, Node content) {
        slot.getChildren().setAll(content);
    }

    // =========================================================================
    // Hover animation
    // =========================================================================
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

    // =========================================================================
    // Main page
    // =========================================================================
    private void buildMain(Stage stage) {
        BorderPane page = new BorderPane();

        // log area
        debugArea = new TextArea();
        debugArea.setEditable(false);
        debugArea.getStyleClass().add("log-area");
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
        page.setCenter(debugArea);

        // bottom bar
        usernameBtn = btn("Select Account","btn","btn-account");
        if (!settings.getUsername().isEmpty()) usernameBtn.setText(settings.getUsername());
        profileBtn = btn(activeProfile.getName()+" ▾","btn","btn-profile");
        profileBtn.setStyle("-fx-background-color:"+activeProfile.getColor()+";");
        Button manageBtn  = btn("⊞ Profiles","btn","btn-dark");
        Button installBtn = btn("⬇ Install","btn","btn-dark");
        Button settingsBtn= btn("⚙","btn","btn-icon");
        Button playBtn    = btn("LAUNCH","btn","btn-launch");
        hover(usernameBtn); hover(profileBtn); hover(manageBtn); hover(installBtn); hover(settingsBtn); hover(playBtn);

        Region s1=new Region(); HBox.setHgrow(s1,Priority.ALWAYS);
        Region s2=new Region(); HBox.setHgrow(s2,Priority.ALWAYS);
        HBox bar = new HBox(10,usernameBtn,s1,profileBtn,s2,manageBtn,installBtn,settingsBtn,playBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        Label credit = new Label("OnyxLauncher  •  AmirCoffee");
        credit.getStyleClass().add("label-hint");
        VBox bottom = new VBox(6,bar,credit);
        bottom.getStyleClass().add("bottom-bar");
        page.setBottom(bottom);

        // popups
        refreshAccountPopup();
        refreshProfilePopup();

        // actions
        settingsBtn.setOnAction(e -> showPage(slotSettings));
        manageBtn.setOnAction(e  -> { buildProfileList(); showPage(slotProfileList); });
        installBtn.setOnAction(e -> showPage(slotInstall));

        usernameBtn.setOnAction(e -> {
            boolean open=!accountPopup.isVisible();
            profilePopup.setVisible(false); accountPopup.setVisible(open);
            if(open) positionAbove(accountPopup,usernameBtn);
        });
        profileBtn.setOnAction(e -> {
            boolean open=!profilePopup.isVisible();
            accountPopup.setVisible(false); profilePopup.setVisible(open);
            if(open) positionAbove(profilePopup,profileBtn);
        });
        page.setOnMouseClicked(e->{accountPopup.setVisible(false);profilePopup.setVisible(false);});

        playBtn.setOnAction(e -> {
            String user=settings.getUsername();
            if(user.isEmpty()){alert("No account","Select an account first."); return;}
            if(activeProfile.getVersion().isEmpty()){alert("No version","Edit the profile and set a Minecraft version."); return;}
            playBtn.setText("LAUNCHING…"); playBtn.setDisable(true);
            new Thread(()->{ try {
                Process proc=GameRunner.launchGame(user,activeProfile);
                if(proc!=null){
                    isGameRunning=true;
                    Platform.runLater(()->hideToTray(stage));
                    new BufferedReader(new InputStreamReader(proc.getInputStream()))
                        .lines().forEach(ln->System.out.println("[MC] "+ln));
                    proc.waitFor(); isGameRunning=false;
                    Platform.runLater(()->{restoreFromTray(stage);playBtn.setText("LAUNCH");playBtn.setDisable(false);});
                }
            }catch(Exception ex){ex.printStackTrace();
                Platform.runLater(()->{playBtn.setText("LAUNCH");playBtn.setDisable(false);});
            }}).start();
        });

        setSlot(slotMain, page);
    }

    private void refreshProfileBtn() {
        profileBtn.setText(activeProfile.getName()+" ▾");
        profileBtn.setStyle("-fx-background-color:"+activeProfile.getColor()+";");
    }

    // =========================================================================
    // Account popup
    // =========================================================================
    private void refreshAccountPopup() {
        accountPopup.getChildren().clear();
        Label h=new Label("Accounts"); h.getStyleClass().add("popup-header");
        accountPopup.getChildren().add(h);
        for(String u:settings.getUsernameHistory()){
            boolean active=u.equals(settings.getUsername());
            Button b=popupItem(u,active?"#1db954":"#cccccc");
            b.setOnAction(e->{settings.setUsername(u);settings.save();usernameBtn.setText(u);accountPopup.setVisible(false);});
            accountPopup.getChildren().add(b);
        }
        accountPopup.getChildren().add(new Separator());
        Button add=popupItem("+ Add Account","#1db954");
        add.setOnAction(e->{accountPopup.setVisible(false);openAddAccount();});
        accountPopup.getChildren().add(add);
    }

    // =========================================================================
    // Profile quick-switcher popup
    // =========================================================================
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

    // =========================================================================
    // Overlay – add account dialog
    // =========================================================================
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
                usernameBtn.setText(name); refreshAccountPopup();
                slotOverlay.setVisible(false);
            }
        });
        cancel.setOnAction(ev->slotOverlay.setVisible(false));
        field.setOnAction(ev->ok.fire());

        dlg.getChildren().addAll(title,field,ok,cancel);
        backdrop.getChildren().add(dlg);
        slotOverlay.setVisible(true);
    }

    // =========================================================================
    // Settings page
    // =========================================================================
    private void buildSettings(Stage stage) {
        VBox content = new VBox(16);
        content.setPadding(new Insets(28,32,28,32));
        content.setStyle("-fx-background-color:#111111;");

        Label title=new Label("Settings"); title.setStyle("-fx-text-fill:white;-fx-font-size:20px;-fx-font-weight:bold;");
        Button back=btn("← Back","btn","btn-back"); hover(back); back.setOnAction(e->showPage(slotMain));
        BorderPane hdr=new BorderPane(); hdr.setLeft(title); hdr.setRight(back);

        Label lbl=sLbl("Default .minecraft Path  (blank = auto-detect)");
        TextField pathF=field(settings.getCustomMcPath(),"Leave blank for default");
        Button browse=btn("Browse…","btn","btn-dark"); hover(browse);
        browse.setOnAction(e->{DirectoryChooser dc=new DirectoryChooser(); File d=dc.showDialog(stage); if(d!=null)pathF.setText(d.getAbsolutePath());});
        HBox row=new HBox(8,pathF,browse); HBox.setHgrow(pathF,Priority.ALWAYS);

        Button save=btn("Save & Back","btn","btn-primary"); save.setPrefHeight(40); hover(save);
        save.setOnAction(e->{settings.setCustomMcPath(pathF.getText().trim()); settings.save(); showPage(slotMain);});

        content.getChildren().addAll(hdr,lbl,row,new Separator(),save);
        ScrollPane sp=new ScrollPane(content); sp.setFitToWidth(true); sp.getStyleClass().add("scroll-pane");
        setSlot(slotSettings, sp);
    }

    // =========================================================================
    // Install page
    // =========================================================================
    private void buildInstall() {
        VBox root=new VBox(0); root.setStyle("-fx-background-color:#111111;");

        Label title=new Label("Install Minecraft"); title.setStyle("-fx-text-fill:white;-fx-font-size:20px;-fx-font-weight:bold;");
        Button back=btn("← Back","btn","btn-back"); hover(back); back.setOnAction(e->showPage(slotMain));
        BorderPane hdr=new BorderPane(); hdr.setLeft(title); hdr.setRight(back);
        hdr.getStyleClass().add("page-header"); hdr.setPadding(new Insets(18,24,14,24));

        installTabPane=new TabPane();
        installTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        installTabPane.getTabs().addAll(
            versionTab("Release","release"), versionTab("Snapshot","snapshot"),
            versionTab("Old Beta","old_beta"), versionTab("Old Alpha","old_alpha"),
            fabricTab()
        );
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
                    alert("Installed!",sel+" installed.\nClick LAUNCH to play!"); showPage(slotMain);});
            }catch(Exception ex){ex.printStackTrace(); Platform.runLater(()->{st.setText("Error: "+ex.getMessage()); pb.setProgress(0); installBtn.setDisable(false);});}}).start();
        });
        return tab;
    }

    private Tab fabricTab() {
        Tab tab=new Tab("Fabric");
        VBox box=new VBox(14); box.setPadding(new Insets(18,24,18,24)); box.setStyle("-fx-background-color:#111111;");

        installMcCombo=new ComboBox<>(); installMcCombo.setPrefWidth(280); installMcCombo.setPromptText("Minecraft Version…");
        installLoaderCombo=new ComboBox<>(); installLoaderCombo.setPrefWidth(280); installLoaderCombo.setPromptText("Loader Version…");
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
                alert("Installed!",fid+" is ready!"); showPage(slotMain);});
        }catch(Exception ex){ex.printStackTrace(); Platform.runLater(()->{installStatus.setText("Error: "+ex.getMessage()); installBar.setProgress(0); installStartBtn.setDisable(false);});}}).start();
    }

    // =========================================================================
    // Profile list page  – rebuilds slot content, does NOT create a new slot
    // =========================================================================
    private void buildProfileList() {
        VBox root=new VBox(0); root.setStyle("-fx-background-color:#111111;");

        Label title=new Label("Profiles"); title.setStyle("-fx-text-fill:white;-fx-font-size:20px;-fx-font-weight:bold;");
        Button newBtn =btn("+ New Profile","btn","btn-primary"); hover(newBtn);
        Button backBtn=btn("← Back","btn","btn-back"); hover(backBtn);
        newBtn.setOnAction(e->{ Profile p=Profile.createDefault("New Profile"); pm.upsert(p); buildProfileEdit(p); showPage(slotProfileEdit); });
        backBtn.setOnAction(e->showPage(slotMain));
        BorderPane hdr=new BorderPane(); hdr.setLeft(title); hdr.setRight(new HBox(8,newBtn,backBtn));
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

        // icon: Minecraft grass block emoji + active indicator
        Label icon = new Label(isActive ? "🟢" : "⬜");
        icon.setStyle("-fx-font-size:22px;");

        // active badge
        Label activeBadge = new Label(isActive ? " ACTIVE" : "");
        activeBadge.setStyle("-fx-text-fill:"+p.getColor()+";-fx-font-size:10px;-fx-font-weight:bold;"
            +"-fx-background-color:"+p.getColor()+"22;-fx-padding:1 5;-fx-background-radius:4;");

        Label name=new Label(p.getName()); name.getStyleClass().add("profile-name");
        HBox nameRow=new HBox(6, icon, name, activeBadge); nameRow.setAlignment(Pos.CENTER_LEFT);

        // version with type label
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
            buildProfileList(); showPage(slotProfileList);
        });
        card.setOnMouseClicked(ev->{
            if(ev.getPickResult().getIntersectedNode()==editBtn||ev.getPickResult().getIntersectedNode()==modsBtn||ev.getPickResult().getIntersectedNode()==delBtn) return;
            activeProfile=p; settings.setActiveProfileId(p.getId()); settings.save();
            refreshProfileBtn(); refreshProfilePopup(); showPage(slotMain);
        });
        card.getChildren().addAll(nameRow, verRow, dir, new Separator(), actions);
        return card;
    }

    // =========================================================================
    // Profile edit page
    // =========================================================================
    private void buildProfileEdit(Profile p) {
        if (p == null) p = Profile.createDefault("Default");
        final Profile prof = p;

        VBox content = new VBox(12);
        content.setPadding(new Insets(24, 32, 32, 32));
        content.setStyle("-fx-background-color:#111111;");

        // ── Header ────────────────────────────────────────────────────────────
        Label title = new Label("Edit Profile  –  " + prof.getName());
        title.setStyle("-fx-text-fill:white;-fx-font-size:20px;-fx-font-weight:bold;");
        Button backBtn = btn("← Back", "btn", "btn-back"); hover(backBtn);
        backBtn.setOnAction(e -> { buildProfileList(); showPage(slotProfileList); });
        BorderPane hdr = new BorderPane(); hdr.setLeft(title); hdr.setRight(backBtn);

        // ── Name ──────────────────────────────────────────────────────────────
        TextField nameF = field(prof.getName(), "Profile Name");

        // ── Colour ────────────────────────────────────────────────────────────
        String[] COLS = {"#1db954","#4CAF50","#2196F3","#9C27B0","#F44336","#FF9800","#00BCD4","#607D8B"};
        ToggleGroup tg = new ToggleGroup();
        HBox colRow = new HBox(8);
        for (String c : COLS) {
            ToggleButton tb = new ToggleButton(); tb.getStyleClass().add("toggle-button");
            tb.setStyle("-fx-background-color:" + c + ";"); tb.setToggleGroup(tg); tb.setUserData(c);
            if (prof.getColor().equalsIgnoreCase(c)) tb.setSelected(true);
            colRow.getChildren().add(tb);
        }

        // ── Minecraft Version ─────────────────────────────────────────────────
        ComboBox<String> verCombo = new ComboBox<>();
        verCombo.setEditable(true);
        verCombo.setMaxWidth(Double.MAX_VALUE);   // fill available width
        verCombo.setPromptText("Select or type version…");
        // populate from installed versions
        File vd = new File(AppConfig.getMinecraftDir(), "versions");
        if (vd.isDirectory()) {
            File[] vdirs = vd.listFiles(File::isDirectory);
            if (vdirs != null) {
                java.util.Arrays.sort(vdirs, (a, b) -> b.getName().compareTo(a.getName()));
                for (File f2 : vdirs) verCombo.getItems().add(f2.getName());
            }
        }
        if (!prof.getVersion().isEmpty()) verCombo.setValue(prof.getVersion());

        // version info label (shows loader type)
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

        // ── Game directory ────────────────────────────────────────────────────
        TextField dirF = field(prof.getGameDir(), "Leave blank to use default .minecraft");
        Button browseDir = btn("Browse…", "btn", "btn-dark"); hover(browseDir);
        browseDir.setOnAction(e -> { DirectoryChooser dc = new DirectoryChooser(); File d = dc.showDialog(null); if (d != null) dirF.setText(d.getAbsolutePath()); });
        HBox dirRow = new HBox(8, dirF, browseDir); HBox.setHgrow(dirF, Priority.ALWAYS);

        // ── Custom content paths ──────────────────────────────────────────────
        // helper: one labeled path row
        // Mods path
        TextField modsPathF = field(prof.getModsPath(), "blank = <gameDir>/mods");
        Button browseMods = btn("Browse…", "btn", "btn-dark"); hover(browseMods);
        browseMods.setOnAction(e -> { DirectoryChooser dc = new DirectoryChooser(); File d = dc.showDialog(null); if (d != null) modsPathF.setText(d.getAbsolutePath()); });
        HBox modsRow = new HBox(8, modsPathF, browseMods); HBox.setHgrow(modsPathF, Priority.ALWAYS);

        // ResourcePacks path
        TextField rpPathF = field(prof.getResourcePacksPath(), "blank = <gameDir>/resourcepacks");
        Button browseRp = btn("Browse…", "btn", "btn-dark"); hover(browseRp);
        browseRp.setOnAction(e -> { DirectoryChooser dc = new DirectoryChooser(); File d = dc.showDialog(null); if (d != null) rpPathF.setText(d.getAbsolutePath()); });
        HBox rpRow = new HBox(8, rpPathF, browseRp); HBox.setHgrow(rpPathF, Priority.ALWAYS);

        // Shaders path
        TextField shPathF = field(prof.getShadersPath(), "blank = <gameDir>/shaderpacks");
        Button browseSh = btn("Browse…", "btn", "btn-dark"); hover(browseSh);
        browseSh.setOnAction(e -> { DirectoryChooser dc = new DirectoryChooser(); File d = dc.showDialog(null); if (d != null) shPathF.setText(d.getAbsolutePath()); });
        HBox shRow = new HBox(8, shPathF, browseSh); HBox.setHgrow(shPathF, Priority.ALWAYS);

        // ── RAM ───────────────────────────────────────────────────────────────
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

        // ── JVM flags ─────────────────────────────────────────────────────────
        // TextArea so multiple flags are easy to read/edit
        TextArea jvmArea = new TextArea(prof.getJvmArgs());
        jvmArea.setPrefRowCount(3);
        jvmArea.setWrapText(true);
        jvmArea.setPromptText("-XX:+UseG1GC -Xss4M -Dfml.readTimeout=90 …");
        jvmArea.setStyle("-fx-control-inner-background:#1a1a1a;-fx-text-fill:#e0e0e0;-fx-border-color:#2a2a2a;-fx-border-radius:6;-fx-background-radius:6;-fx-font-family:monospace;-fx-font-size:12px;");

        // JVM presets
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

        // ── Resolution ────────────────────────────────────────────────────────
        TextField resW = field(String.valueOf(prof.getResWidth()), "1280"); resW.setPrefWidth(70);
        TextField resH = field(String.valueOf(prof.getResHeight()), "720"); resH.setPrefWidth(70);
        Label xLbl = new Label("×"); xLbl.setStyle("-fx-text-fill:#888;");
        CheckBox fsBox = new CheckBox("Fullscreen"); fsBox.setSelected(prof.isFullscreen());
        Region rSp = new Region(); HBox.setHgrow(rSp, Priority.ALWAYS);
        HBox resRow = new HBox(8, resW, xLbl, resH, rSp, fsBox); resRow.setAlignment(Pos.CENTER_LEFT);

        // ── Java executable ───────────────────────────────────────────────────
        // Scan /usr/lib/jvm for available JVMs
        ComboBox<String> javaCombo = new ComboBox<>();
        javaCombo.setEditable(true);
        javaCombo.setMaxWidth(Double.MAX_VALUE);
        javaCombo.setPromptText("Auto-detect (recommended)");
        // add "Auto" option
        javaCombo.getItems().add("Auto-detect");
        File jvmRoot = new File("/usr/lib/jvm");
        if (jvmRoot.isDirectory()) {
            File[] jvms = jvmRoot.listFiles(f -> f.isDirectory() && new File(f,"bin/java").canExecute());
            if (jvms != null) {
                java.util.Arrays.sort(jvms, (a,b) -> b.getName().compareTo(a.getName()));
                for (File j : jvms) javaCombo.getItems().add(new File(j,"bin/java").getAbsolutePath());
            }
        }
        // also check update-alternatives
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

        // ── Mods manager shortcut ─────────────────────────────────────────────
        Button modsBtn = btn("📦  Manage Mods / ResourcePacks / Shaders", "btn", "btn-dark");
        modsBtn.setMaxWidth(Double.MAX_VALUE); modsBtn.setPrefHeight(40); hover(modsBtn);
        modsBtn.setOnAction(e -> { buildContentManager(prof); showPage(slotContentManager); });

        // ── Save ──────────────────────────────────────────────────────────────
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
            buildProfileList(); showPage(slotProfileList);
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

    // =========================================================================
    // Content manager (Mods / ResourcePacks / Shaders)
    // =========================================================================
    private void buildContentManager(Profile profile) {
        if(profile==null) profile=activeProfile;
        final Profile prof=profile;

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

        // left: installed
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

        // right: search
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

    // =========================================================================
    // Tray
    // =========================================================================
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

    // =========================================================================
    // UI helpers
    // =========================================================================
    private VBox popupCard() {
        VBox v=new VBox(2); v.getStyleClass().add("popup-card");
        v.setVisible(false); v.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);
        return v;
    }
    private Button btn(String text, String... css) {
        Button b=new Button(text); b.getStyleClass().addAll(css); return b;
    }
    private Button popupItem(String text, String color) {
        Button b=new Button(text); b.setMaxWidth(Double.MAX_VALUE);
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

    public static void main(String[] args) { launch(args); }
}
