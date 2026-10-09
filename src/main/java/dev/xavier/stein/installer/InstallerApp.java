/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.installer;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The Stein Loader installer: a window (default) or the command line.
 *
 * <p>The window starts with the language (English, Spanish or Portuguese; English preselected) and
 * everything comes out in it, the log included. If the chosen folder already has Stein Loader, the
 * main button becomes Update. The end shows in the window itself, with Open .minecraft and Finish.
 *
 * <pre>
 * [--mc folder]                           window, with the folder already filled in
 * --cli [--mc folder] [--lang en|es|pt] [--no-loader] [--ram GB] [--uninstall]
 *      [--mods all|none|keep|combat,hud,...]  default: updates the mods already there
 * --cli --list                            only lists the .minecraft folders found
 * </pre>
 */
public final class InstallerApp {

    private static final Color DARK = new Color(0x101014);
    private static final Color GRAY = new Color(0x9A9A9A);
    private static final Color OK = new Color(0x1E7B45);
    private static final Color ERROR = new Color(0xB3261E);
    private static final String[] RAM = {"2 GB", "3 GB", "4 GB", "6 GB", "8 GB"};

    /** The author shown in the window header; change it here only. */
    private static final String VENDOR = "X4vieer";

    private static final Dimension BODY = new Dimension(700, 470);
    private static final Path LOG_FILE =
            Paths.get(System.getProperty("java.io.tmpdir"), "stein-loader-installer.log");

    private final JFrame frame = new JFrame();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final Path initialDir;
    private Messages m = Messages.of(Messages.Lang.EN);

    private JLabel headerTitle;
    private JLabel headerSub;
    private JTextField dir;
    private JComboBox<String> dirBox;

    /** The launcher of each folder found, for the list. */
    private final Map<String, String> launchers = new java.util.concurrent.ConcurrentHashMap<>();

    private JButton browse;
    private JCheckBox loader;
    private JLabel modsLabel;
    private JPanel modsPanel;
    private final Map<String, JCheckBox> modBoxes = new java.util.LinkedHashMap<>();
    private JComboBox<String> ram;
    private JLabel status;
    private JProgressBar bar;
    private JTextArea logArea;
    private final CardLayout actionCards = new CardLayout();
    private JPanel actions;
    private JButton primary;
    private JButton uninstallButton;
    private boolean running;
    private Map<String, Object> existing;

    private InstallerApp(Path initialDir) {
        this.initialDir = initialDir;
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--cli")) {
            System.exit(cli(args));
        }
        Path mc =
                args.length > 1 && args[0].equals("--mc") ? Paths.get(args[1]) : defaultMinecraft();
        SwingUtilities.invokeLater(() -> new InstallerApp(mc).open());
    }

    /**
     * The payload folder: next to the jar in the jpackage app, or extracted from the jar itself
     * when the installer is the single file ({@link Bundle}).
     */
    static Path payload(Messages m) throws Exception {
        Path self =
                Paths.get(
                        InstallerApp.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        Path beside = self.getParent().resolve("payload");
        if (Files.isDirectory(beside)) {
            return beside;
        }
        Path jar = Bundle.self();
        if (jar != null) {
            return Bundle.extract(jar, "payload/");
        }
        throw new Install.InstallException(m.t("incomplete", beside));
    }

    /**
     * Each system's default .minecraft: {@code %APPDATA%\.minecraft} on Windows, {@code
     * ~/Library/Application Support/minecraft} on macOS (no dot, and with spaces: it is the Mojang
     * launcher's) and {@code ~/.minecraft} on Linux.
     */
    static Path defaultMinecraft() {
        Path home = Paths.get(System.getProperty("user.home"));
        if (Install.MACOS) {
            return home.resolve("Library").resolve("Application Support").resolve("minecraft");
        }
        String appdata = System.getenv("APPDATA");
        return appdata != null && Install.WINDOWS
                ? Paths.get(appdata, ".minecraft")
                : home.resolve(".minecraft");
    }

    /**
     * .minecraft folders with 1.8.9 downloaded: the launcher's and those of portable modpacks (a
     * launcher.exe that points APPDATA at its own folder) in the user's folder, up to 5 levels
     * deep.
     */
    static List<Path> findMinecrafts() {
        Set<Path> found = new LinkedHashSet<>();
        Path def = defaultMinecraft().toAbsolutePath().normalize();
        if (has189(def)) {
            found.add(def);
        }
        Path home = Paths.get(System.getProperty("user.home"));
        for (String root :
                new String[] {"Desktop", "Documents", "Downloads", "OneDrive", "Games"}) {
            Path r = home.resolve(root);
            if (!Files.isDirectory(r)) {
                continue;
            }
            try {
                Files.walkFileTree(
                        r,
                        java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                        5,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(
                                    Path d, BasicFileAttributes attrs) {
                                String name =
                                        d.getFileName() == null ? "" : d.getFileName().toString();
                                if (name.equals(".minecraft")) {
                                    if (has189(d)) {
                                        found.add(d.toAbsolutePath().normalize());
                                    }
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                // Hidden and dependency folders: no player .minecraft in there.
                                boolean skip =
                                        !d.equals(r)
                                                && (name.startsWith(".")
                                                        || name.equals("node_modules"));
                                return skip
                                        ? FileVisitResult.SKIP_SUBTREE
                                        : FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                                // At the last level folders arrive here, not in preVisitDirectory.
                                if (attrs.isDirectory()
                                        && f.getFileName().toString().equals(".minecraft")
                                        && has189(f)) {
                                    found.add(f.toAbsolutePath().normalize());
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path f, IOException e) {
                                return FileVisitResult
                                        .CONTINUE; // no access (Windows junctions): carry on
                            }
                        });
            } catch (IOException ignored) {
                // a folder that vanished mid-search
            }
        }
        return new ArrayList<>(found);
    }

    static boolean has189(Path mc) {
        return Files.isRegularFile(mc.resolve("versions/1.8.9/1.8.9.json"));
    }

    /**
     * The chosen folder or the .minecraft inside it (up to 3 levels): choosing the modpack folder,
     * or the one above .minecraft, lands in the same place.
     */
    static Path resolveMinecraft(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        if (has189(abs) || !Files.isDirectory(abs)) {
            return abs;
        }
        try (Stream<Path> s =
                Files.find(
                        abs,
                        3,
                        (f, a) ->
                                a.isDirectory()
                                        && f.getFileName() != null
                                        && f.getFileName().toString().equals(".minecraft")
                                        && has189(f))) {
            return s.findFirst().map(f -> f.toAbsolutePath().normalize()).orElse(abs);
        } catch (IOException | java.io.UncheckedIOException e) {
            return abs;
        }
    }

    /**
     * Is the game from this folder open? That is the Java the installer put in the version (the
     * profile's javaDir) running.
     */
    static boolean gameRunning(Path runtimeDir) {
        Path rt = runtimeDir.toAbsolutePath().normalize();
        for (String c : processPaths()) {
            try {
                if (Paths.get(c).toAbsolutePath().normalize().startsWith(rt)) {
                    return true;
                }
            } catch (InvalidPathException e) {
                // odd path in the process list: not the game
            }
        }
        return false;
    }

    /**
     * The launcher rewrites launcher_profiles.json when it closes: with it open, the new profile
     * can vanish. Covers the official one and a launcher.exe next to the .minecraft (a portable
     * modpack, which points APPDATA at its own folder).
     */
    static boolean launcherRunning(Path mc) {
        Path beside = mc.toAbsolutePath().normalize().getParent();
        for (String c : processPaths()) {
            String exe = c.toLowerCase(java.util.Locale.ROOT);
            if (exe.endsWith("minecraftlauncher.exe")) {
                return true;
            }
            try {
                if (exe.endsWith("launcher.exe")
                        && beside != null
                        && beside.equals(Paths.get(c).getParent())) {
                    return true;
                }
            } catch (InvalidPathException e) {
                // odd path in the process list: not the launcher being looked for
            }
        }
        return false;
    }

    /**
     * The path of each running process. Java 9's {@code ProcessHandle} would do this in one line,
     * but the installer is compiled for Java 8 (what people who play 1.8.9 have); on Windows, where
     * it matters, PowerShell answers the same thing. If it is missing or slow, the list comes back
     * empty: when in doubt the installer carries on and the "close the launcher" warning just does
     * not appear.
     */
    private static List<String> processPaths() {
        List<String> output = new ArrayList<>();
        if (!Install.WINDOWS) {
            // Outside Windows there is no launcher.exe to warn about, and calling PowerShell would
            // only be an error in the log.
            return output;
        }
        try {
            // PowerShell, not wmic: Windows 11 dropped wmic from new installs, and the "close the
            // launcher" warning simply stopped appearing.
            Process p =
                    new ProcessBuilder(
                                    "powershell",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-Command",
                                    "Get-Process | ForEach-Object { $_.Path }")
                            .redirectErrorStream(true)
                            .start();
            try (BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(
                                    p.getInputStream(),
                                    java.nio.charset.StandardCharsets.ISO_8859_1))) {
                for (String line; (line = r.readLine()) != null; ) {
                    line = line.trim();
                    if (!line.isEmpty() && line.indexOf('\\') >= 0) {
                        output.add(line);
                    }
                }
            }
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            System.out.println("could not list the processes: " + e);
        }
        return output;
    }

    /**
     * Fetches the mod list on another thread and, when it arrives, shows one checkbox per mod. What
     * is installed comes ticked; on a new install, everything comes ticked: whoever downloads the
     * installer wants the mods, and unticking is one click. Without internet the section simply
     * does not appear and the mods folder is left as it is.
     */
    private void fetchMods() {
        new SwingWorker<List<Mods.Mod>, Void>() {
            @Override
            protected List<Mods.Mod> doInBackground() {
                return Mods.index();
            }

            @Override
            protected void done() {
                List<Mods.Mod> list;
                try {
                    list = get();
                } catch (Exception e) {
                    return;
                }
                if (list.isEmpty()) {
                    return;
                }
                Set<String> have =
                        installedModIds(resolveMinecraft(Paths.get(dir.getText().trim())));
                GridBagConstraints p = new GridBagConstraints();
                p.gridx = 0;
                p.anchor = GridBagConstraints.WEST;
                for (Mods.Mod mod : list) {
                    JCheckBox box =
                            new JCheckBox(
                                    mod.name + "  " + mod.version,
                                    have.isEmpty() || have.contains(mod.id));
                    box.setToolTipText(mod.description);
                    modBoxes.put(mod.id, box);
                    modsPanel.add(box, p);
                }
                JLabel hint = new JLabel(m.t("modsHint"));
                hint.setFont(hint.getFont().deriveFont(Font.PLAIN, hint.getFont().getSize() - 1f));
                hint.setForeground(Color.GRAY);
                modsPanel.add(hint, p);
                modsLabel.setVisible(true);
                modsPanel.setVisible(true);
                frame.pack();
            }
        }.execute();
    }

    /**
     * What {@code --mods} asks for: {@code all} (everything on the list), {@code none} (nothing,
     * and whatever is there is removed), {@code keep} (leave the folder alone) or ids separated by
     * commas. Without the option, the installer updates what is already installed and adds nothing,
     * which is what does not surprise someone who only wanted to update.
     */
    private static java.util.Set<String> requestedMods(String arg) {
        String v = arg.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("keep")) {
            return null;
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (v.equals("none")) {
            return out;
        }
        if (v.equals("all")) {
            for (Mods.Mod mod : Mods.index()) {
                out.add(mod.id);
            }
            return out;
        }
        for (String id : v.split(",")) {
            if (!id.trim().isEmpty()) {
                out.add(id.trim());
            }
        }
        return out;
    }

    /** The mods already in the folder: the default is to update those, adding no others. */
    private static java.util.Set<String> installedModIds(Path mc) {
        return new java.util.LinkedHashSet<>(Mods.installedMods(mc.resolve("mods")).keySet());
    }

    private static int cli(String[] args) {
        Path mc = defaultMinecraft();
        Messages m = Messages.of(Messages.Lang.EN);
        Install.Options o = new Install.Options();
        boolean uninstall = false;
        boolean list = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--list":
                    list = true;
                    break;
                case "--mc":
                    mc = resolveMinecraft(Paths.get(args[++i]));
                    break;
                case "--lang":
                    m = Messages.of(Messages.Lang.parse(args[++i]));
                    break;
                case "--no-loader":
                    o.loader = false;
                    break;
                case "--ram":
                    o.ramGb = Integer.parseInt(args[++i]);
                    break;
                case "--uninstall":
                    uninstall = true;
                    break;
                // --mods all | none | combat,hud | keep (default: only touches what is already
                // there)
                case "--mods":
                    o.mods = requestedMods(args[++i]);
                    break;
                default:
                    System.err.println(m.t("cliUnknown", args[i]));
                    return 2;
            }
        }
        if (list) {
            for (Path p : findMinecrafts()) {
                String launcher = Install.launcherName(p);
                System.out.println(p + (launcher != null ? "  (" + launcher + ")" : ""));
            }
            return 0;
        }
        if (launcherRunning(mc)) {
            System.out.println(m.t("launcherOpen"));
        }
        if (o.mods == null && !uninstall) {
            o.mods = installedModIds(mc); // without --mods: update what is already there
        }
        try {
            Install in = new Install(mc, payload(m), o, m, System.out::println);
            if (uninstall) {
                in.uninstall();
            } else {
                in.install();
            }
            return 0;
        } catch (Install.InstallException e) {
            System.out.println(m.t("error", e.getMessage()));
            return 1;
        } catch (Exception e) {
            System.out.println(m.t("error", e));
            e.printStackTrace(System.out);
            return 1;
        }
    }

    // ---- window ----

    private void open() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the default Swing look stays
        }
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        try {
            frame.setIconImage(ImageIO.read(new ByteArrayInputStream(Install.icon())));
        } catch (Exception ignored) {
            // no icon
        }
        body.setPreferredSize(BODY);
        body.add(languageCard(), "lang");

        JPanel root = new JPanel(new BorderLayout());
        root.add(header(), BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        frame.setContentPane(root);
        applyTexts();
        frame.pack();
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(20, 0));
        header.setBackground(DARK);
        header.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));
        try (InputStream in = InstallerApp.class.getResourceAsStream("/stein-loader-logo.png")) {
            if (in != null) {
                // Stein Loader logo, 270 x 192: at 64 px high.
                Image logo = ImageIO.read(in).getScaledInstance(90, 64, Image.SCALE_SMOOTH);
                header.add(new JLabel(new ImageIcon(logo)), BorderLayout.WEST);
            }
        } catch (Exception ignored) {
            // no logo
        }
        JPanel titles = new JPanel(new GridBagLayout());
        titles.setOpaque(false);
        headerTitle = new JLabel();
        headerTitle.setForeground(Color.WHITE);
        headerTitle.setFont(headerTitle.getFont().deriveFont(Font.BOLD, 22f));
        headerSub = new JLabel();
        headerSub.setForeground(GRAY);
        JLabel by = new JLabel("By: " + VENDOR);
        by.setForeground(GRAY);
        GridBagConstraints t = new GridBagConstraints();
        t.gridx = 0;
        t.anchor = GridBagConstraints.WEST;
        titles.add(headerTitle, t);
        titles.add(headerSub, t);
        titles.add(by, t);
        header.add(titles, BorderLayout.CENTER);
        return header;
    }

    private void applyTexts() {
        frame.setTitle(m.t("windowTitle", Install.VERSION));
        headerTitle.setText(m.t("headerTitle"));
        headerSub.setText(m.t("headerSub", Install.VERSION));
    }

    /**
     * First screen: the language. English is preselected; the button already speaks the chosen
     * language.
     */
    private JPanel languageCard() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 0, 4, 0);
        JLabel prompt = new JLabel("Choose your language  ·  Elige tu idioma  ·  Escolha o idioma");
        prompt.setFont(prompt.getFont().deriveFont(Font.BOLD, 15f));
        c.insets = new Insets(0, 0, 14, 0);
        p.add(prompt, c);
        c.insets = new Insets(2, 24, 2, 0);
        JButton next = new JButton(Messages.of(Messages.Lang.EN).t("continue"));
        ButtonGroup group = new ButtonGroup();
        Messages.Lang[] chosen = {Messages.Lang.EN};
        for (Messages.Lang lang : Messages.Lang.values()) {
            JRadioButton r = new JRadioButton(lang.label, lang == Messages.Lang.EN);
            r.setFont(r.getFont().deriveFont(14f));
            r.addActionListener(
                    e -> {
                        chosen[0] = lang;
                        next.setText(Messages.of(lang).t("continue"));
                    });
            group.add(r);
            p.add(r, c);
        }
        c.insets = new Insets(18, 0, 0, 0);
        c.anchor = GridBagConstraints.CENTER;
        next.setPreferredSize(new Dimension(160, 30));
        next.addActionListener(
                e -> {
                    m = Messages.of(chosen[0]);
                    applyTexts();
                    body.add(mainCard(), "main");
                    cards.show(body, "main");
                    detect();
                    frame.getRootPane().setDefaultButton(primary);
                    refresh(true);
                    Path f = folder();
                    if (f != null && launcherRunning(f)) {
                        append(m.t("launcherOpen"));
                    }
                });
        p.add(next, c);
        frame.getRootPane().setDefaultButton(next);
        return p;
    }

    private JPanel mainCard() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 18, 4, 18));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;
        form.add(new JLabel(m.t("folder")), c);
        // Editable list: the folders found (detect), or any typed or browsed one.
        dirBox = new JComboBox<>();
        dirBox.setEditable(true);
        dirBox.setRenderer(
                new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean selected,
                            boolean focus) {
                        super.getListCellRendererComponent(list, value, index, selected, focus);
                        String launcher = launchers.get(String.valueOf(value));
                        setText(value + (launcher != null ? "   (" + launcher + ")" : ""));
                        return this;
                    }
                });
        dir = (JTextField) dirBox.getEditor().getEditorComponent();
        dir.setText(initialDir.toString());
        dir.setColumns(30);
        dir.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            @Override
                            public void insertUpdate(DocumentEvent e) {
                                refresh(true);
                            }

                            @Override
                            public void removeUpdate(DocumentEvent e) {
                                refresh(true);
                            }

                            @Override
                            public void changedUpdate(DocumentEvent e) {
                                refresh(true);
                            }
                        });
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(dirBox, c);
        browse = new JButton(m.t("browse"));
        browse.addActionListener(e -> browse());
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(browse, c);

        c.gridy = 1;
        c.gridx = 0;
        form.add(new JLabel(m.t("components")), c);
        JPanel parts = new JPanel(new GridBagLayout());
        // The Bridge is the engine: it always goes in (the Loader depends on it), ticked and
        // locked.
        JCheckBox bridge = new JCheckBox(m.t("bridge"), true);
        bridge.setEnabled(false);
        loader = new JCheckBox(m.t("loader"), true);
        GridBagConstraints p = new GridBagConstraints();
        p.gridx = 0;
        p.anchor = GridBagConstraints.WEST;
        parts.add(bridge, p);
        parts.add(loader, p);
        c.gridx = 1;
        c.gridwidth = 2;
        form.add(parts, c);

        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 1;
        form.add(new JLabel(m.t("memory")), c);
        ram = new JComboBox<>(RAM);
        ram.setSelectedItem("3 GB"); // the default; whoever installed before keeps their choice
        c.gridx = 1;
        form.add(ram, c);

        // The mods: the list comes from the internet, so its place starts empty and fills in when
        // (and if) the fetch answers; the window never waits for the network to appear.
        c.gridy = 3;
        c.gridx = 0;
        c.gridwidth = 1;
        modsLabel = new JLabel(m.t("modsTitle"));
        modsLabel.setVisible(false);
        form.add(modsLabel, c);
        modsPanel = new JPanel(new GridBagLayout());
        modsPanel.setVisible(false);
        c.gridx = 1;
        c.gridwidth = 2;
        form.add(modsPanel, c);
        fetchMods();

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 18, 14, 18));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        status = new JLabel(" ");
        top.add(status, BorderLayout.NORTH);
        bar = new JProgressBar(0, 6);
        top.add(bar, BorderLayout.SOUTH);
        logArea = new JTextArea(7, 60);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        actions = new JPanel(actionCards);
        JPanel run = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        uninstallButton = new JButton(m.t("uninstall"));
        uninstallButton.addActionListener(e -> run(true));
        primary = new JButton(m.t("install"));
        primary.addActionListener(e -> run(false));
        run.add(uninstallButton);
        run.add(primary);
        JPanel done = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton open = new JButton(m.t("openMinecraft"));
        open.addActionListener(e -> openMinecraft());
        JButton finish = new JButton(m.t("finish"));
        finish.addActionListener(
                e -> {
                    frame.dispose();
                    System.exit(0);
                });
        done.add(open);
        done.add(finish);
        actions.add(run, "run");
        actions.add(done, "done");

        bottom.add(top, BorderLayout.NORTH);
        bottom.add(new JScrollPane(logArea), BorderLayout.CENTER);
        bottom.add(actions, BorderLayout.SOUTH);

        JPanel card = new JPanel(new BorderLayout());
        card.add(form, BorderLayout.NORTH);
        card.add(bottom, BorderLayout.CENTER);
        return card;
    }

    /**
     * Looks for the folders in the background; each goes into the list, with its launcher, and into
     * the log.
     */
    private void detect() {
        new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() {
                List<Path> found = findMinecrafts();
                for (Path p : found) {
                    String launcher = Install.launcherName(p);
                    if (launcher != null) {
                        launchers.put(p.toString(), launcher);
                    }
                }
                return found;
            }

            @Override
            protected void done() {
                List<Path> found;
                try {
                    found = get();
                } catch (Exception e) {
                    return;
                }
                String typed = dir.getText();
                for (Path p : found) {
                    dirBox.addItem(p.toString());
                }
                dir.setText(typed); // the first item would end up in the field
                if (found.size() > 1) {
                    append(m.t("detected", found.size()));
                    for (Path p : found) {
                        String launcher = launchers.get(p.toString());
                        append("  " + p + (launcher != null ? "  (" + launcher + ")" : ""));
                    }
                }
            }
        }.execute();
    }

    private Path folder() {
        try {
            return Paths.get(dir.getText().trim());
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * An existing install in the folder: Update button, Uninstall visible and the earlier choices.
     */
    private void refresh(boolean adoptChoices) {
        if (running || primary == null) {
            return;
        }
        Path mc = folder();
        existing = mc == null ? null : Install.installed(mc);
        boolean installed = existing != null;
        primary.setText(m.t(installed ? "updateButton" : "install"));
        uninstallButton.setVisible(installed);
        if (installed && adoptChoices) {
            loader.setSelected(!Boolean.FALSE.equals(existing.get("loader")));
            Object gb = existing.get("ramGb");
            if (gb != null) {
                ram.setSelectedItem(gb.toString() + " GB");
            }
        }
        setStatus(
                installed ? m.t("installedHere", existing.get("version")) : m.t("ready"),
                null,
                false);
    }

    private void setStatus(String text, Color color, boolean bold) {
        String safe =
                text.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\n", "<br>");
        status.setText("<html>" + (bold ? "<b>" + safe + "</b>" : safe) + "</html>");
        status.setForeground(color != null ? color : UIManager.getColor("Label.foreground"));
    }

    private void browse() {
        JFileChooser ch = new JFileChooser(dir.getText());
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        ch.setDialogTitle(m.t("chooser"));
        if (ch.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            // Entering the folder and clicking Open, selecting nothing, means the current folder.
            java.io.File f =
                    ch.getSelectedFile() != null ? ch.getSelectedFile() : ch.getCurrentDirectory();
            dir.setText(resolveMinecraft(f.toPath()).toString());
        }
    }

    private void append(String s) {
        logArea.append(s + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        // A copy in %TEMP%: what the installer did, and in which folder.
        try {
            Files.write(
                    LOG_FILE,
                    (s + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // without the file, only the window remains
        }
    }

    private void setInputsEnabled(boolean on) {
        dirBox.setEnabled(on);
        browse.setEnabled(on);
        loader.setEnabled(on);
        ram.setEnabled(on);
        primary.setEnabled(on);
        uninstallButton.setEnabled(on);
    }

    private void openMinecraft() {
        Path mc = folder();
        if (mc == null) {
            return;
        }
        try {
            Desktop.getDesktop().open(mc.toFile());
        } catch (Exception e) {
            try {
                new ProcessBuilder("explorer.exe", mc.toString()).start();
            } catch (Exception ignored) {
                append(m.t("error", e.getMessage()));
            }
        }
    }

    private void run(boolean uninstall) {
        Path picked = folder();
        if (picked == null) {
            return;
        }
        // The modpack folder (or another above .minecraft) also works: the .minecraft inside is
        // used.
        Path mc = resolveMinecraft(picked);
        Install.Options o = new Install.Options();
        o.loader = loader.isSelected();
        o.ramGb = Integer.parseInt(((String) ram.getSelectedItem()).split(" ")[0]);
        if (!modBoxes.isEmpty()) {
            o.mods = new LinkedHashSet<>();
            for (Map.Entry<String, JCheckBox> e : modBoxes.entrySet()) {
                if (e.getValue().isSelected()) {
                    o.mods.add(e.getKey());
                }
            }
        }
        boolean update = !uninstall && Install.installed(mc) != null;
        append("== " + java.time.LocalDateTime.now().withNano(0) + "  " + mc);
        running = true;
        setInputsEnabled(false);
        bar.setValue(0);
        setStatus(m.t(uninstall ? "uninstalling" : update ? "updating" : "installing"), null, true);
        new SwingWorker<Void, String>() {
            private Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    Install in =
                            new Install(
                                    mc,
                                    payload(m),
                                    o,
                                    m,
                                    new Install.Log() {
                                        @Override
                                        public void line(String s) {
                                            publish(s);
                                        }

                                        @Override
                                        public void progress(int done, int total) {
                                            SwingUtilities.invokeLater(() -> bar.setValue(done));
                                        }
                                    });
                    if (uninstall) {
                        in.uninstall();
                    } else {
                        in.install();
                    }
                } catch (Exception e) {
                    error = e;
                }
                return null;
            }

            @Override
            protected void process(List<String> lines) {
                lines.forEach(InstallerApp.this::append);
            }

            @Override
            protected void done() {
                running = false;
                if (error != null) {
                    String msg =
                            error instanceof Install.InstallException
                                    ? error.getMessage()
                                    : error.toString();
                    append(m.t("error", msg));
                    setStatus(m.t("error", msg), ERROR, true);
                    setInputsEnabled(true);
                    return;
                }
                bar.setValue(bar.getMaximum());
                String title =
                        m.t(uninstall ? "removedOk" : update ? "updatedOk" : "installedOk")
                                + "\n"
                                + m.t("inFolder", mc);
                setStatus(
                        uninstall ? title : title + "\n" + m.t("nextStep", Install.PROFILE_NAME),
                        OK,
                        true);
                actionCards.show(actions, "done");
            }
        }.execute();
    }
}
