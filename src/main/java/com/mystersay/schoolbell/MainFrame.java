package com.mystersay.schoolbell;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MainFrame extends JFrame {
    private static final String VERSION = "1.4.1";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("dd.MM.yyyy  HH:mm:ss");
    private static final DateTimeFormatter NEXT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final AppSettings settings;
    private final ScheduleStore store;
    private final AudioPlayer audioPlayer = new AudioPlayer();
    private final ScheduleTableModel tableModel;
    private final JTable table;

    private final CardLayout pagesLayout = new CardLayout();
    private final JPanel pages = new JPanel(pagesLayout);
    private final JLabel pageTitle = new JLabel("Головна");
    private final JLabel clockLabel = new JLabel();
    private final JLabel sidebarStatus = new JLabel();

    private final JLabel nextTimeLabel = new JLabel("—");
    private final JLabel nextDetailsLabel = new JLabel("Наступний дзвінок не запланований");
    private final JLabel nextCountdownLabel = new JLabel("—");
    private final UIComponents.ModernButton skipNextButton = new UIComponents.ModernButton("Пропустити наступний дзвінок");

    private final JTextField startSoundField = new JTextField();
    private final JTextField endSoundField = new JTextField();
    private final JSlider volumeSlider = new JSlider(0, 100);
    private final JLabel volumeLabel = new JLabel();
    private final JTextArea logArea = new JTextArea();

    private final JCheckBox scheduleEnabledBox = new JCheckBox("Розклад активний");
    private final JCheckBox autoStartBox = new JCheckBox("Автоматичний запуск разом із системою");
    private final JCheckBox highPriorityBox = new JCheckBox("Максимальний пріоритет для SchoolBell (High)");
    private final JCheckBox minimizeToTrayBox = new JCheckBox("Згортати в системний трей при закритті");

    private final JCheckBox neptunAlertsBox = new JCheckBox("Тривоги — NEPTUN");
    private final JCheckBox alertsInUaBox = new JCheckBox("Тривоги — alerts.in.ua");
    private final JPasswordField alertsInUaTokenField = new JPasswordField();
    private JPanel alertsInUaTokenPanel;
    private final JComboBox<String> alertOblastCombo = new JComboBox<>();
    private final JComboBox<String> alertDistrictCombo = new JComboBox<>();
    private final JTextField alertSoundField = new JTextField();
    private final JTextField allClearSoundField = new JTextField();
    private final JLabel alertStatusLabel = new JLabel("Статус: вимкнено");
    private boolean updatingAlertSelectors;

    private final UIComponents.NavButton homeNav = new UIComponents.NavButton("Головна");
    private final UIComponents.NavButton tableNav = new UIComponents.NavButton("Таблиця");
    private final UIComponents.NavButton settingsNav = new UIComponents.NavButton("Налаштування");

    private SchedulerService scheduler;
    private NeptunAlertService neptunAlertService;
    private AlertsInUaAlertService alertsInUaAlertService;
    private SchedulerService.NextBell lastNextBell;
    private TrayIcon trayIcon;
    private boolean exiting;

    public MainFrame(AppSettings settings, ScheduleStore store) {
        super("SchoolBell");
        this.settings = settings;
        this.store = store;
        this.tableModel = new ScheduleTableModel(settings.lessons, settings.bells, this::saveQuietly);
        this.table = new JTable(tableModel);
        setUndecorated(true);
        buildUi();
        startServices();
    }

    private void buildUi() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1040, 680));
        setSize(1220, 790);
        setLocationRelativeTo(null);
        getRootPane().setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));

        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(Theme.BG);
        setContentPane(shell);
        shell.add(buildTitleBar(), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(Theme.BG);
        body.add(buildSidebar(), BorderLayout.WEST);

        JPanel content = new JPanel(new BorderLayout(0, 16));
        content.setBackground(Theme.BG);
        content.setBorder(new EmptyBorder(18, 20, 20, 20));
        content.add(buildPageHeader(), BorderLayout.NORTH);

        pages.setBackground(Theme.BG);
        pages.add(buildHomePage(), "HOME");
        pages.add(buildTablePage(), "TABLE");
        pages.add(buildSettingsPage(), "SETTINGS");
        content.add(pages, BorderLayout.CENTER);
        body.add(content, BorderLayout.CENTER);
        shell.add(body, BorderLayout.CENTER);

        showPage("HOME", homeNav, "Головна");
        setupTray();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                handleCloseRequest();
            }
        });
    }

    private JPanel buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.SIDEBAR);
        bar.setBorder(new EmptyBorder(7, 14, 7, 7));
        bar.setPreferredSize(new Dimension(10, 42));

        JLabel title = new JLabel("SchoolBell " + VERSION + " — шкільний розклад дзвінків");
        title.setForeground(Color.WHITE);
        title.setFont(Theme.preferredFont(13f, Font.BOLD));
        bar.add(title, BorderLayout.WEST);

        JPanel windowButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        windowButtons.setOpaque(false);
        UIComponents.ModernButton minimize = windowButton("—");
        UIComponents.ModernButton maximize = windowButton("□");
        UIComponents.ModernButton close = windowButton("×");
        close.setPalette(Theme.SIDEBAR, Theme.DANGER, Theme.DANGER);
        minimize.addActionListener(e -> setState(Frame.ICONIFIED));
        maximize.addActionListener(e -> toggleMaximize());
        close.addActionListener(e -> handleCloseRequest());
        windowButtons.add(minimize);
        windowButtons.add(maximize);
        windowButtons.add(close);
        bar.add(windowButtons, BorderLayout.EAST);

        installWindowDrag(bar);
        installWindowDrag(title);
        return bar;
    }

    private UIComponents.ModernButton windowButton(String text) {
        UIComponents.ModernButton button = new UIComponents.ModernButton(text, Theme.SIDEBAR, Theme.HOVER, Theme.CARD_2, 8);
        button.setFont(Theme.preferredFont(16f, Font.BOLD));
        button.setBorder(new EmptyBorder(3, 12, 4, 12));
        return button;
    }

    private void installWindowDrag(Component component) {
        MouseAdapter adapter = new MouseAdapter() {
            private Point last;
            @Override public void mousePressed(MouseEvent e) {
                last = e.getLocationOnScreen();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (last == null || (getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) return;
                Point now = e.getLocationOnScreen();
                Point loc = getLocation();
                setLocation(loc.x + now.x - last.x, loc.y + now.y - last.y);
                last = now;
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) toggleMaximize();
            }
        };
        component.addMouseListener(adapter);
        component.addMouseMotionListener(adapter);
    }

    private void toggleMaximize() {
        if ((getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) setExtendedState(Frame.NORMAL);
        else setExtendedState(Frame.MAXIMIZED_BOTH);
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(Theme.SIDEBAR);
        sidebar.setPreferredSize(new Dimension(218, 10));
        sidebar.setBorder(new EmptyBorder(20, 14, 18, 14));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel brand = new JLabel("SchoolBell");
        brand.setForeground(Color.WHITE);
        brand.setFont(Theme.preferredFont(25f, Font.BOLD));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel caption = new JLabel("Керування дзвінками");
        caption.setForeground(Color.WHITE);
        caption.setFont(Theme.preferredFont(12f, Font.PLAIN));
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(brand);
        top.add(Box.createVerticalStrut(4));
        top.add(caption);
        top.add(Box.createVerticalStrut(30));

        configureNav(homeNav, "HOME", "Головна");
        configureNav(tableNav, "TABLE", "Таблиця");
        configureNav(settingsNav, "SETTINGS", "Налаштування");
        homeNav.setAlignmentX(Component.LEFT_ALIGNMENT);
        tableNav.setAlignmentX(Component.LEFT_ALIGNMENT);
        settingsNav.setAlignmentX(Component.LEFT_ALIGNMENT);
        homeNav.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        tableNav.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        settingsNav.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        top.add(homeNav);
        top.add(Box.createVerticalStrut(8));
        top.add(tableNav);
        top.add(Box.createVerticalStrut(8));
        top.add(settingsNav);
        sidebar.add(top, BorderLayout.NORTH);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        sidebarStatus.setForeground(Color.WHITE);
        sidebarStatus.setFont(Theme.preferredFont(12f, Font.BOLD));
        sidebarStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel version = new JLabel("Java desktop • v4");
        version.setForeground(Color.WHITE);
        version.setFont(Theme.preferredFont(11f, Font.PLAIN));
        version.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(sidebarStatus);
        bottom.add(Box.createVerticalStrut(6));
        bottom.add(version);
        sidebar.add(bottom, BorderLayout.SOUTH);
        updateScheduleStatus();
        return sidebar;
    }

    private void configureNav(UIComponents.NavButton button, String page, String title) {
        button.addActionListener(e -> showPage(page, button, title));
    }

    private void showPage(String page, UIComponents.NavButton selected, String title) {
        pagesLayout.show(pages, page);
        homeNav.setSelectedState(selected == homeNav);
        tableNav.setSelectedState(selected == tableNav);
        settingsNav.setSelectedState(selected == settingsNav);
        pageTitle.setText(title);
    }

    private JPanel buildPageHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        pageTitle.setForeground(Color.WHITE);
        pageTitle.setFont(Theme.preferredFont(26f, Font.BOLD));
        clockLabel.setForeground(Color.WHITE);
        clockLabel.setFont(Theme.preferredFont(13f, Font.BOLD));
        header.add(pageTitle, BorderLayout.WEST);
        header.add(clockLabel, BorderLayout.EAST);
        return header;
    }

    private JPanel buildHomePage() {
        JPanel page = new JPanel(new BorderLayout(0, 18));
        page.setBackground(Theme.BG);

        UIComponents.RoundedPanel nextCard = new UIComponents.RoundedPanel(new BorderLayout(18, 0));
        nextCard.setBorder(new EmptyBorder(18, 22, 18, 22));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JLabel nextTitle = new JLabel("НАСТУПНИЙ ДЗВІНОК");
        nextTitle.setForeground(Color.WHITE);
        nextTitle.setFont(Theme.preferredFont(12f, Font.BOLD));
        nextDetailsLabel.setForeground(Color.WHITE);
        nextDetailsLabel.setFont(Theme.preferredFont(16f, Font.BOLD));
        nextCountdownLabel.setForeground(Color.WHITE);
        nextCountdownLabel.setFont(Theme.preferredFont(13f, Font.PLAIN));
        left.add(nextTitle);
        left.add(Box.createVerticalStrut(8));
        left.add(nextDetailsLabel);
        left.add(Box.createVerticalStrut(5));
        left.add(nextCountdownLabel);

        nextTimeLabel.setForeground(Color.WHITE);
        nextTimeLabel.setFont(Theme.preferredFont(42f, Font.BOLD));
        nextCard.add(left, BorderLayout.CENTER);
        nextCard.add(nextTimeLabel, BorderLayout.EAST);
        page.add(nextCard, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);
        JPanel bellWrap = new JPanel();
        bellWrap.setOpaque(false);
        bellWrap.setLayout(new BoxLayout(bellWrap, BoxLayout.Y_AXIS));
        UIComponents.RoundBellButton bell = new UIComponents.RoundBellButton();
        bell.setAlignmentX(Component.CENTER_ALIGNMENT);
        bell.addActionListener(e -> {
            log("РУЧНИЙ ДЗВІНОК.");
            playSound(true, "Ручний дзвінок");
        });
        JLabel help = new JLabel("Натисни для ручного запуску дзвінка");
        help.setForeground(Color.WHITE);
        help.setFont(Theme.preferredFont(13f, Font.PLAIN));
        help.setAlignmentX(Component.CENTER_ALIGNMENT);
        bellWrap.add(bell);
        bellWrap.add(Box.createVerticalStrut(16));
        bellWrap.add(help);
        center.add(bellWrap);
        page.add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        bottom.setOpaque(false);
        skipNextButton.setPreferredSize(new Dimension(310, 46));
        skipNextButton.addActionListener(e -> skipNextBell());
        bottom.add(skipNextButton);
        page.add(bottom, BorderLayout.SOUTH);
        return page;
    }

    private JPanel buildTablePage() {
        JPanel page = new JPanel(new BorderLayout(0, 14));
        page.setBackground(Theme.BG);

        UIComponents.RoundedPanel tableCard = new UIComponents.RoundedPanel(new BorderLayout());
        tableCard.setBorder(new EmptyBorder(10, 10, 10, 10));
        configureTable();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.CARD);
        tableCard.add(scroll, BorderLayout.CENTER);
        page.add(tableCard, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0));
        actions.setOpaque(false);
        UIComponents.ModernButton add = new UIComponents.ModernButton(
                "+ Додати урок", Theme.ACCENT_2, Theme.ACCENT, Theme.ACCENT_2, 14);
        UIComponents.ModernButton addBell = new UIComponents.ModernButton(
                "+ Додати дзвінок", Theme.ACCENT_2, Theme.ACCENT, Theme.ACCENT_2, 14);
        UIComponents.ModernButton edit = new UIComponents.ModernButton("Редагувати");
        UIComponents.ModernButton delete = new UIComponents.ModernButton("Видалити", Theme.FIELD, Theme.DANGER, Theme.DANGER, 14);
        UIComponents.ModernButton duplicate = new UIComponents.ModernButton("Дублювати");
        UIComponents.ModernButton sort = new UIComponents.ModernButton("Сортувати за часом");
        add.addActionListener(e -> addLesson());
        addBell.addActionListener(e -> addBell());
        edit.addActionListener(e -> editSelected());
        delete.addActionListener(e -> deleteSelected());
        duplicate.addActionListener(e -> duplicateSelected());
        sort.addActionListener(e -> sortSchedule());
        actions.add(add);
        actions.add(addBell);
        actions.add(edit);
        actions.add(delete);
        actions.add(duplicate);
        actions.add(sort);
        page.add(actions, BorderLayout.SOUTH);
        return page;
    }

    private void configureTable() {
        table.setRowHeight(38);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.setShowVerticalLines(false);
        table.setGridColor(Theme.BORDER);
        table.setBackground(Theme.CARD);
        table.setForeground(Color.WHITE);
        table.setSelectionBackground(new Color(53, 62, 88));
        table.setSelectionForeground(Color.WHITE);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(10, 38));
        table.getTableHeader().setBackground(Theme.CARD_2);
        table.getTableHeader().setForeground(Color.WHITE);
        table.getColumnModel().getColumn(0).setPreferredWidth(78);
        table.getColumnModel().getColumn(0).setMaxWidth(88);
        table.getColumnModel().getColumn(1).setPreferredWidth(82);
        table.getColumnModel().getColumn(1).setMaxWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(280);
        table.getColumnModel().getColumn(3).setPreferredWidth(130);
        table.getColumnModel().getColumn(3).setMaxWidth(155);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setMaxWidth(105);
        table.getColumnModel().getColumn(5).setPreferredWidth(125);
        table.getColumnModel().getColumn(5).setMaxWidth(180);
        table.getColumnModel().getColumn(6).setPreferredWidth(150);
        table.getColumnModel().getColumn(6).setMaxWidth(190);
        if (table.getTableHeader().getDefaultRenderer() instanceof DefaultTableCellRenderer renderer) {
            renderer.setHorizontalAlignment(SwingConstants.LEFT);
            renderer.setBackground(Theme.CARD_2);
            renderer.setForeground(Color.WHITE);
        }
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) editSelected();
            }
        });
    }

    private JPanel buildSettingsPage() {
        JPanel page = new JPanel(new BorderLayout(16, 0));
        page.setBackground(Theme.BG);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        UIComponents.RoundedPanel audio = buildAudioCard();
        UIComponents.RoundedPanel alerts = buildAlertSettingsCard();
        UIComponents.RoundedPanel app = buildApplicationSettingsCard();
        audio.setAlignmentX(Component.LEFT_ALIGNMENT);
        alerts.setAlignmentX(Component.LEFT_ALIGNMENT);
        app.setAlignmentX(Component.LEFT_ALIGNMENT);
        audio.setMaximumSize(new Dimension(Integer.MAX_VALUE, audio.getPreferredSize().height));
        alerts.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1000));
        app.setMaximumSize(new Dimension(Integer.MAX_VALUE, app.getPreferredSize().height));
        left.add(audio);
        left.add(Box.createVerticalStrut(16));
        left.add(alerts);
        left.add(Box.createVerticalStrut(16));
        left.add(app);

        JScrollPane leftScroll = new JScrollPane(left);
        leftScroll.setBorder(BorderFactory.createEmptyBorder());
        leftScroll.getViewport().setBackground(Theme.BG);
        leftScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        leftScroll.setPreferredSize(new Dimension(610, 10));

        UIComponents.RoundedPanel journal = buildJournalCard();
        page.add(leftScroll, BorderLayout.CENTER);
        page.add(journal, BorderLayout.EAST);
        journal.setPreferredSize(new Dimension(300, 10));
        return page;
    }

    private UIComponents.RoundedPanel buildAudioCard() {
        UIComponents.RoundedPanel card = new UIComponents.RoundedPanel(new GridBagLayout());
        card.setBorder(new EmptyBorder(18, 18, 18, 18));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 5, 6, 5);
        gc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = sectionTitle("Звуки дзвінка");
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 4; gc.weightx = 1;
        card.add(title, gc);

        startSoundField.setEditable(false);
        endSoundField.setEditable(false);
        startSoundField.setForeground(Color.WHITE);
        endSoundField.setForeground(Color.WHITE);
        startSoundField.setBackground(Theme.FIELD);
        endSoundField.setBackground(Theme.FIELD);
        startSoundField.setText(pathText(settings.startSound));
        endSoundField.setText(pathText(settings.endSound));

        addAudioRow(card, gc, 1, "Початок уроку", startSoundField, true);
        addAudioRow(card, gc, 2, "Кінець уроку", endSoundField, false);

        gc.gridy = 3; gc.gridx = 0; gc.gridwidth = 1; gc.weightx = 0;
        JLabel volumeTitle = new JLabel("Гучність");
        volumeTitle.setForeground(Color.WHITE);
        card.add(volumeTitle, gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 1;
        volumeSlider.setValue(settings.volume);
        volumeSlider.setBackground(Theme.CARD);
        volumeSlider.setForeground(Color.WHITE);
        volumeSlider.addChangeListener(e -> {
            settings.volume = volumeSlider.getValue();
            volumeLabel.setText(settings.volume + "%");
            if (!volumeSlider.getValueIsAdjusting()) saveQuietly();
        });
        card.add(volumeSlider, gc);
        gc.gridx = 3; gc.gridwidth = 1; gc.weightx = 0;
        volumeLabel.setForeground(Color.WHITE);
        volumeLabel.setText(settings.volume + "%");
        card.add(volumeLabel, gc);

        UIComponents.ModernButton stop = new UIComponents.ModernButton("Зупинити звук");
        stop.addActionListener(e -> audioPlayer.stop());
        gc.gridy = 4; gc.gridx = 0; gc.gridwidth = 4; gc.weightx = 1;
        card.add(stop, gc);
        return card;
    }

    private void addAudioRow(JPanel card, GridBagConstraints gc, int row, String label, JTextField field, boolean start) {
        gc.gridy = row; gc.gridx = 0; gc.gridwidth = 1; gc.weightx = 0;
        JLabel title = new JLabel(label);
        title.setForeground(Color.WHITE);
        card.add(title, gc);
        gc.gridx = 1; gc.weightx = 1;
        card.add(field, gc);

        UIComponents.ModernButton choose = new UIComponents.ModernButton("Обрати");
        UIComponents.ModernButton test = new UIComponents.ModernButton("Тест");
        choose.addActionListener(e -> chooseSound(start));
        test.addActionListener(e -> playSound(start, "Тест звуку"));
        gc.gridx = 2; gc.weightx = 0;
        card.add(choose, gc);
        gc.gridx = 3;
        card.add(test, gc);
    }

    private UIComponents.RoundedPanel buildAlertSettingsCard() {
        UIComponents.RoundedPanel card = new UIComponents.RoundedPanel(new GridBagLayout());
        card.setBorder(new EmptyBorder(18, 18, 18, 18));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 4, 6, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 4; gc.weightx = 1;
        card.add(sectionTitle("Повітряні тривоги"), gc);

        JLabel providerLabel = new JLabel("Сервіс");
        providerLabel.setForeground(Color.WHITE);
        gc.gridy = 1; gc.gridx = 0; gc.gridwidth = 1; gc.weightx = 0;
        card.add(providerLabel, gc);

        configureCheckBox(neptunAlertsBox, settings.alertProvider == AppSettings.AlertProvider.NEPTUN);
        configureCheckBox(alertsInUaBox, settings.alertProvider == AppSettings.AlertProvider.ALERTS_IN_UA);
        JPanel providers = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        providers.setOpaque(false);
        providers.add(neptunAlertsBox);
        providers.add(alertsInUaBox);
        gc.gridx = 1; gc.gridwidth = 3; gc.weightx = 1;
        card.add(providers, gc);

        alertsInUaTokenPanel = new JPanel(new GridBagLayout());
        alertsInUaTokenPanel.setOpaque(false);
        GridBagConstraints tc = new GridBagConstraints();
        tc.insets = new Insets(0, 0, 0, 8);
        tc.fill = GridBagConstraints.HORIZONTAL;
        tc.gridx = 0; tc.gridy = 0; tc.weightx = 0;
        JLabel tokenLabel = new JLabel("API токен");
        tokenLabel.setForeground(Color.WHITE);
        alertsInUaTokenPanel.add(tokenLabel, tc);

        alertsInUaTokenField.setBackground(Theme.FIELD);
        alertsInUaTokenField.setForeground(Color.WHITE);
        alertsInUaTokenField.setCaretColor(Color.WHITE);
        alertsInUaTokenField.setFont(Theme.preferredFont(13f, Font.PLAIN));
        alertsInUaTokenField.setText(settings.alertsInUaToken == null ? "" : settings.alertsInUaToken);
        tc.gridx = 1; tc.weightx = 1;
        alertsInUaTokenPanel.add(alertsInUaTokenField, tc);

        UIComponents.ModernButton saveToken = new UIComponents.ModernButton("Зберегти токен");
        saveToken.addActionListener(e -> saveAlertsInUaToken());
        tc.gridx = 2; tc.weightx = 0; tc.insets = new Insets(0, 0, 0, 0);
        alertsInUaTokenPanel.add(saveToken, tc);

        JLabel tokenHint = new JLabel("<html><div style='width:430px'>Токен не записується в schedule.properties. На Windows — "
                + AlertsInUaTokenStore.ENV_NAME + "; якщо це недоступно — файл "
                + AlertsInUaTokenStore.FALLBACK_FILE_NAME + " біля програми.</div></html>");
        tokenHint.setForeground(Color.WHITE);
        tokenHint.setFont(Theme.preferredFont(10.5f, Font.PLAIN));
        tc.gridx = 0; tc.gridy = 1; tc.gridwidth = 3; tc.weightx = 1; tc.insets = new Insets(5, 0, 0, 0);
        alertsInUaTokenPanel.add(tokenHint, tc);

        gc.gridy = 2; gc.gridx = 0; gc.gridwidth = 4; gc.weightx = 1;
        card.add(alertsInUaTokenPanel, gc);
        alertsInUaTokenPanel.setVisible(settings.alertProvider == AppSettings.AlertProvider.ALERTS_IN_UA);

        configureAlertCombo(alertOblastCombo);
        configureAlertCombo(alertDistrictCombo);
        populateAlertSelectors();

        gc.gridy = 3; gc.gridx = 0; gc.gridwidth = 1; gc.weightx = 0;
        JLabel oblastLabel = new JLabel("Область");
        oblastLabel.setForeground(Color.WHITE);
        card.add(oblastLabel, gc);
        gc.gridx = 1; gc.gridwidth = 3; gc.weightx = 1;
        card.add(alertOblastCombo, gc);

        gc.gridy = 4; gc.gridx = 0; gc.gridwidth = 1; gc.weightx = 0;
        JLabel districtLabel = new JLabel("Район");
        districtLabel.setForeground(Color.WHITE);
        card.add(districtLabel, gc);
        gc.gridx = 1; gc.gridwidth = 3; gc.weightx = 1;
        card.add(alertDistrictCombo, gc);

        alertSoundField.setEditable(false);
        allClearSoundField.setEditable(false);
        alertSoundField.setForeground(Color.WHITE);
        allClearSoundField.setForeground(Color.WHITE);
        alertSoundField.setBackground(Theme.FIELD);
        allClearSoundField.setBackground(Theme.FIELD);
        alertSoundField.setText(pathText(settings.alertSound));
        allClearSoundField.setText(pathText(settings.allClearSound));
        addAlertAudioRow(card, gc, 5, "Тривога", alertSoundField, true);
        addAlertAudioRow(card, gc, 6, "Відбій", allClearSoundField, false);

        gc.gridy = 7; gc.gridx = 0; gc.gridwidth = 4; gc.weightx = 1;
        alertStatusLabel.setForeground(Color.WHITE);
        alertStatusLabel.setFont(Theme.preferredFont(12f, Font.BOLD));
        card.add(alertStatusLabel, gc);

        JPanel sources = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sources.setOpaque(false);
        JLabel sourcesTitle = new JLabel("Джерела:");
        sourcesTitle.setForeground(Color.WHITE);
        sources.add(sourcesTitle);
        sources.add(createWebLink("NEPTUN", "https://neptun.in.ua/"));
        JLabel slash = new JLabel("•");
        slash.setForeground(Color.WHITE);
        sources.add(slash);
        sources.add(createWebLink("alerts.in.ua", "https://alerts.in.ua/"));
        gc.gridy = 8;
        card.add(sources, gc);

        JLabel warning = new JLabel("<html><div style='width:470px'>SchoolBell використовує зовнішні інформаційні сервіси. "
                + "Сповіщення програми не замінюють офіційні сигнали повітряної тривоги.</div></html>");
        warning.setForeground(Color.WHITE);
        warning.setFont(Theme.preferredFont(11f, Font.PLAIN));
        gc.gridy = 9;
        card.add(warning, gc);

        refreshAlertStatusText(null);

        neptunAlertsBox.addActionListener(e -> {
            AppSettings.AlertProvider target = neptunAlertsBox.isSelected()
                    ? AppSettings.AlertProvider.NEPTUN : AppSettings.AlertProvider.NONE;
            setAlertProvider(target, card);
        });
        alertsInUaBox.addActionListener(e -> {
            AppSettings.AlertProvider target = alertsInUaBox.isSelected()
                    ? AppSettings.AlertProvider.ALERTS_IN_UA : AppSettings.AlertProvider.NONE;
            setAlertProvider(target, card);
        });

        alertOblastCombo.addActionListener(e -> {
            if (updatingAlertSelectors) return;
            updateDistrictsForSelectedOblast(null);
            syncAlertSelectionFromUi();
            resetAlertServices();
            checkActiveAlertServiceSoon();
            saveQuietly();
        });
        alertDistrictCombo.addActionListener(e -> {
            if (updatingAlertSelectors) return;
            syncAlertSelectionFromUi();
            refreshAlertStatusText(null);
            resetAlertServices();
            checkActiveAlertServiceSoon();
            saveQuietly();
        });
        return card;
    }

    private void setAlertProvider(AppSettings.AlertProvider provider, JComponent layoutRoot) {
        settings.alertProvider = provider == null ? AppSettings.AlertProvider.NONE : provider;
        neptunAlertsBox.setSelected(settings.alertProvider == AppSettings.AlertProvider.NEPTUN);
        alertsInUaBox.setSelected(settings.alertProvider == AppSettings.AlertProvider.ALERTS_IN_UA);
        if (alertsInUaTokenPanel != null) {
            alertsInUaTokenPanel.setVisible(settings.alertProvider == AppSettings.AlertProvider.ALERTS_IN_UA);
        }

        syncAlertSelectionFromUi();
        resetAlertServices();
        refreshAlertStatusText(null);
        saveQuietly();

        if (settings.alertProvider == AppSettings.AlertProvider.NONE) {
            log("Попередження про повітряні тривоги вимкнено.");
        } else {
            log("Сервіс повітряних тривог: " + settings.alertProvider.displayName()
                    + "; місце: " + alertSelectionText() + ".");
            if (settings.alertProvider == AppSettings.AlertProvider.ALERTS_IN_UA
                    && (settings.alertsInUaToken == null || settings.alertsInUaToken.isBlank())) {
                log("alerts.in.ua: введи та збережи API токен.");
            }
            checkActiveAlertServiceSoon();
        }

        if (layoutRoot != null) {
            layoutRoot.revalidate();
            layoutRoot.repaint();
        }
    }

    private void addAlertAudioRow(JPanel card, GridBagConstraints gc, int row, String label, JTextField field, boolean alert) {
        gc.gridy = row; gc.gridx = 0; gc.gridwidth = 1; gc.weightx = 0;
        JLabel title = new JLabel(label);
        title.setForeground(Color.WHITE);
        card.add(title, gc);
        gc.gridx = 1; gc.weightx = 1;
        card.add(field, gc);
        UIComponents.ModernButton choose = new UIComponents.ModernButton("Обрати");
        UIComponents.ModernButton test = new UIComponents.ModernButton("Тест");
        choose.addActionListener(e -> chooseAlertSound(alert));
        test.addActionListener(e -> playSound(alert ? settings.alertSound : settings.allClearSound,
                alert ? "Тест звуку тривоги" : "Тест звуку відбою"));
        gc.gridx = 2; gc.weightx = 0;
        card.add(choose, gc);
        gc.gridx = 3;
        card.add(test, gc);
    }

    private static void configureAlertCombo(JComboBox<String> combo) {
        combo.setBackground(Theme.FIELD);
        combo.setForeground(Color.WHITE);
        combo.setFont(Theme.preferredFont(13f, Font.PLAIN));
        combo.setMaximumRowCount(14);
    }

    private void populateAlertSelectors() {
        updatingAlertSelectors = true;
        try {
            alertOblastCombo.removeAllItems();
            alertOblastCombo.addItem("Оберіть область");
            for (String oblast : UkraineRegionCatalog.oblasts()) alertOblastCombo.addItem(oblast);
            if (settings.alertOblast != null && !settings.alertOblast.isBlank()) {
                alertOblastCombo.setSelectedItem(settings.alertOblast);
            } else {
                alertOblastCombo.setSelectedIndex(0);
            }
            updateDistrictsForSelectedOblast(settings.alertDistrict);
        } finally {
            updatingAlertSelectors = false;
        }
    }

    private void updateDistrictsForSelectedOblast(String preferred) {
        boolean old = updatingAlertSelectors;
        updatingAlertSelectors = true;
        try {
            String oblast = selectedComboValue(alertOblastCombo, "Оберіть область");
            alertDistrictCombo.removeAllItems();
            alertDistrictCombo.addItem("Оберіть район");
            if (!oblast.isBlank()) {
                for (String district : UkraineRegionCatalog.districts(oblast)) alertDistrictCombo.addItem(district);
            }
            String wanted = preferred == null ? settings.alertDistrict : preferred;
            if (wanted != null && !wanted.isBlank()) alertDistrictCombo.setSelectedItem(wanted);
            if (alertDistrictCombo.getSelectedIndex() < 0) alertDistrictCombo.setSelectedIndex(0);
            alertDistrictCombo.setEnabled(!oblast.isBlank());
        } finally {
            updatingAlertSelectors = old;
        }
    }

    private void syncAlertSelectionFromUi() {
        settings.alertOblast = selectedComboValue(alertOblastCombo, "Оберіть область");
        settings.alertDistrict = selectedComboValue(alertDistrictCombo, "Оберіть район");
        refreshAlertStatusText(null);
    }

    private static String selectedComboValue(JComboBox<String> combo, String placeholder) {
        Object value = combo.getSelectedItem();
        if (value == null) return "";
        String text = value.toString().trim();
        return text.equals(placeholder) ? "" : text;
    }

    private String alertSelectionText() {
        if (settings.alertOblast == null || settings.alertOblast.isBlank()) return "область не вибрана";
        if (settings.alertDistrict == null || settings.alertDistrict.isBlank()) return settings.alertOblast + ", район не вибраний";
        return settings.alertOblast + " • " + settings.alertDistrict;
    }

    private void refreshAlertStatusText(Boolean active) {
        if (settings.alertProvider == AppSettings.AlertProvider.NONE) {
            alertStatusLabel.setText("Статус: попередження вимкнено");
        } else if (settings.alertOblast == null || settings.alertOblast.isBlank()
                || settings.alertDistrict == null || settings.alertDistrict.isBlank()) {
            alertStatusLabel.setText("Статус: оберіть область та район");
        } else if (settings.alertProvider == AppSettings.AlertProvider.ALERTS_IN_UA
                && (settings.alertsInUaToken == null || settings.alertsInUaToken.isBlank())) {
            alertStatusLabel.setText("Статус: введіть API токен alerts.in.ua");
        } else if (active == null) {
            alertStatusLabel.setText("Статус: очікування даних " + settings.alertProvider.displayName() + "…");
        } else if (active) {
            alertStatusLabel.setText("Статус: ПОВІТРЯНА ТРИВОГА");
        } else {
            alertStatusLabel.setText("Статус: тривоги немає");
        }
    }

    private void saveAlertsInUaToken() {
        String token = new String(alertsInUaTokenField.getPassword()).trim();
        AlertsInUaTokenStore.SaveResult result = AlertsInUaTokenStore.save(token);
        if (result.success()) {
            settings.alertsInUaToken = token;
            log(result.message());
            refreshAlertStatusText(null);
            if (alertsInUaAlertService != null) {
                alertsInUaAlertService.resetState();
                if (settings.alertProvider == AppSettings.AlertProvider.ALERTS_IN_UA) alertsInUaAlertService.checkSoon();
            }
        } else {
            log("ПОМИЛКА: " + result.message());
        }
    }

    private void chooseAlertSound(boolean alert) {
        Path current = alert ? settings.alertSound : settings.allClearSound;
        Path path = DarkDialogs.chooseAudio(this, alert ? "Звук повітряної тривоги" : "Звук відбою тривоги", current);
        if (path == null) return;
        if (alert) {
            settings.alertSound = path;
            alertSoundField.setText(path.toString());
        } else {
            settings.allClearSound = path;
            allClearSoundField.setText(path.toString());
        }
        saveQuietly();
        log("Вибрано аудіо " + (alert ? "тривоги" : "відбою") + ": " + path.getFileName());
    }

    private JLabel createWebLink(String text, String url) {
        JLabel link = new JLabel("<html><u>" + text + "</u></html>");
        link.setForeground(Color.WHITE);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(url);
        link.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { openWebsite(url, text); }
        });
        return link;
    }

    private void openWebsite(String url, String name) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception ex) {
            log("Не вдалося відкрити " + name + ": " + ex.getMessage());
        }
    }

    private void resetAlertServices() {
        if (neptunAlertService != null) neptunAlertService.resetState();
        if (alertsInUaAlertService != null) alertsInUaAlertService.resetState();
    }

    private void checkActiveAlertServiceSoon() {
        if (settings.alertProvider == AppSettings.AlertProvider.NEPTUN && neptunAlertService != null) {
            neptunAlertService.checkSoon();
        } else if (settings.alertProvider == AppSettings.AlertProvider.ALERTS_IN_UA && alertsInUaAlertService != null) {
            alertsInUaAlertService.checkSoon();
        }
    }

    private UIComponents.RoundedPanel buildApplicationSettingsCard() {
        UIComponents.RoundedPanel card = new UIComponents.RoundedPanel(new GridBagLayout());
        card.setBorder(new EmptyBorder(18, 18, 18, 18));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 4, 6, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 1;
        card.add(sectionTitle("Робота програми"), gc);

        configureCheckBox(scheduleEnabledBox, settings.scheduleEnabled);
        configureCheckBox(autoStartBox, settings.autoStart);
        configureCheckBox(highPriorityBox, settings.highPriority);
        configureCheckBox(minimizeToTrayBox, settings.minimizeToTray);

        gc.gridy++;
        card.add(scheduleEnabledBox, gc);
        gc.gridy++;
        card.add(autoStartBox, gc);
        gc.gridy++;
        card.add(highPriorityBox, gc);
        gc.gridy++;
        JLabel priorityHint = new JLabel("High — без режиму Realtime, щоб не створювати ризик зависання Windows.");
        priorityHint.setForeground(Color.WHITE);
        priorityHint.setFont(Theme.preferredFont(11f, Font.PLAIN));
        priorityHint.setBorder(new EmptyBorder(0, 24, 2, 0));
        card.add(priorityHint, gc);
        gc.gridy++;
        card.add(minimizeToTrayBox, gc);

        scheduleEnabledBox.addActionListener(e -> {
            settings.scheduleEnabled = scheduleEnabledBox.isSelected();
            updateScheduleStatus();
            saveQuietly();
            log(settings.scheduleEnabled ? "Розклад увімкнено." : "Розклад вимкнено.");
        });

        autoStartBox.addActionListener(e -> {
            boolean wanted = autoStartBox.isSelected();
            autoStartBox.setEnabled(false);
            runSystemAction(() -> SystemIntegration.setAutoStart(wanted), result -> {
                autoStartBox.setEnabled(true);
                if (result.success()) {
                    settings.autoStart = wanted;
                    saveQuietly();
                    log(result.message());
                } else {
                    autoStartBox.setSelected(!wanted);
                    DarkDialogs.message(this, "Автозапуск", result.message());
                    log("ПОМИЛКА: " + result.message());
                }
            });
        });

        highPriorityBox.addActionListener(e -> {
            boolean wanted = highPriorityBox.isSelected();
            highPriorityBox.setEnabled(false);
            runSystemAction(() -> SystemIntegration.applyHighPriority(wanted), result -> {
                highPriorityBox.setEnabled(true);
                if (result.success()) {
                    settings.highPriority = wanted;
                    saveQuietly();
                    log(result.message());
                } else {
                    highPriorityBox.setSelected(!wanted);
                    DarkDialogs.message(this, "Пріоритет", result.message());
                    log("ПОМИЛКА: " + result.message());
                }
            });
        });

        minimizeToTrayBox.addActionListener(e -> {
            settings.minimizeToTray = minimizeToTrayBox.isSelected();
            saveQuietly();
            log(settings.minimizeToTray ? "Згортання в трей увімкнено." : "Згортання в трей вимкнено.");
        });

        UIComponents.ModernButton openConfig = new UIComponents.ModernButton("Відкрити папку налаштувань");
        openConfig.addActionListener(e -> runSystemAction(
                () -> SystemIntegration.openFolder(store.configDir()),
                result -> {
                    if (!result.success()) DarkDialogs.message(this, "Налаштування", result.message());
                    log(result.message());
                }));
        gc.gridy++;
        gc.insets = new Insets(12, 4, 4, 4);
        card.add(openConfig, gc);
        return card;
    }

    private UIComponents.RoundedPanel buildJournalCard() {
        UIComponents.RoundedPanel card = new UIComponents.RoundedPanel(new BorderLayout(0, 12));
        card.setBorder(new EmptyBorder(18, 18, 18, 18));
        card.add(sectionTitle("Журнал"), BorderLayout.NORTH);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBackground(Theme.FIELD);
        logArea.setForeground(Color.WHITE);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));
        scroll.getViewport().setBackground(Theme.FIELD);
        card.add(scroll, BorderLayout.CENTER);
        UIComponents.ModernButton clear = new UIComponents.ModernButton("Очистити журнал");
        clear.addActionListener(e -> logArea.setText(""));
        card.add(clear, BorderLayout.SOUTH);
        return card;
    }

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(Theme.preferredFont(18f, Font.BOLD));
        return label;
    }

    private static void configureCheckBox(JCheckBox box, boolean selected) {
        box.setSelected(selected);
        box.setOpaque(false);
        box.setForeground(Color.WHITE);
        box.setFont(Theme.preferredFont(13f, Font.PLAIN));
    }

    private void startServices() {
        Timer clockTimer = new Timer(500, e -> clockLabel.setText(LocalDateTime.now().format(CLOCK)));
        clockTimer.start();

        scheduler = new SchedulerService(
                () -> new ArrayList<>(settings.lessons),
                () -> new ArrayList<>(settings.bells),
                () -> settings.scheduleEnabled,
                () -> settings.highPriority,
                this::handleBell,
                this::updateNextBell,
                this::handleSkippedBell
        );
        scheduler.start();

        neptunAlertService = new NeptunAlertService(
                () -> settings.alertProvider == AppSettings.AlertProvider.NEPTUN,
                () -> settings.alertOblast,
                () -> settings.alertDistrict,
                event -> handleAirAlertEvent(event.active(), event.oblast(), event.district(), event.initial(), "NEPTUN"),
                message -> SwingUtilities.invokeLater(() -> log(message))
        );
        neptunAlertService.start();

        alertsInUaAlertService = new AlertsInUaAlertService(
                () -> settings.alertProvider == AppSettings.AlertProvider.ALERTS_IN_UA,
                () -> settings.alertsInUaToken,
                () -> settings.alertOblast,
                () -> settings.alertDistrict,
                event -> handleAirAlertEvent(event.active(), event.oblast(), event.district(), event.initial(), "alerts.in.ua"),
                message -> SwingUtilities.invokeLater(() -> log(message))
        );
        alertsInUaAlertService.start();

        log("SchoolBell запущено. Розклад: " + (settings.scheduleEnabled ? "активний" : "вимкнений") + ".");
        if (settings.alertProvider != AppSettings.AlertProvider.NONE) {
            log("Попередження про тривоги: " + settings.alertProvider.displayName() + " • " + alertSelectionText() + ".");
        }

        if (settings.highPriority) {
            runSystemAction(() -> SystemIntegration.applyHighPriority(true), result -> log(result.message()));
        }
        if (settings.autoStart) {
            runSystemAction(() -> SystemIntegration.setAutoStart(true), result -> {
                if (!result.success()) log("ПОМИЛКА автозапуску: " + result.message());
            });
        }
    }

    private void handleAirAlertEvent(boolean active, String oblast, String district, boolean initial, String source) {
        SwingUtilities.invokeLater(() -> {
            // Ignore a late event from a provider that was switched off while its HTTP request was in flight.
            boolean sourceStillSelected = ("NEPTUN".equals(source) && settings.alertProvider == AppSettings.AlertProvider.NEPTUN)
                    || ("alerts.in.ua".equals(source) && settings.alertProvider == AppSettings.AlertProvider.ALERTS_IN_UA);
            if (!sourceStillSelected) return;

            refreshAlertStatusText(active);
            String place = oblast + (district == null || district.isBlank() ? "" : " • " + district);
            if (initial && !active) {
                log(source + ": для «" + place + "» активної повітряної тривоги немає.");
                return;
            }
            if (active) {
                log("ПОВІТРЯНА ТРИВОГА — " + place + " • " + source
                        + (initial ? " (активна на момент запуску перевірки)" : ""));
                playSound(settings.alertSound, "ПОВІТРЯНА ТРИВОГА");
                if (trayIcon != null) {
                    trayIcon.displayMessage("ПОВІТРЯНА ТРИВОГА",
                            place + "\nДані: " + source + ". Перевіряй офіційні сигнали.",
                            TrayIcon.MessageType.WARNING);
                }
            } else {
                log("ВІДБІЙ ПОВІТРЯНОЇ ТРИВОГИ — " + place + " • " + source);
                playSound(settings.allClearSound, "ВІДБІЙ ТРИВОГИ");
                if (trayIcon != null) {
                    trayIcon.displayMessage("Відбій повітряної тривоги",
                            place + "\nДані: " + source + ".", TrayIcon.MessageType.INFO);
                }
            }
        });
    }

    private void handleBell(SchedulerService.BellEvent event) {
        SwingUtilities.invokeLater(() -> {
            String kind;
            Path sound;
            if (event.type() == SchedulerService.EventType.BELL) {
                ScheduledBell bell = event.bell();
                kind = "ДЗВІНОК";
                sound = resolveBellSound(bell);
                log(kind + " — " + bell.name() + " о " + event.scheduledAt().toLocalTime().format(Lesson.TIME_FORMAT)
                        + " • аудіо: " + bell.audioSource().display());
            } else {
                boolean startEvent = event.type() == SchedulerService.EventType.START;
                kind = startEvent ? "ПОЧАТОК" : "КІНЕЦЬ";
                sound = startEvent ? settings.startSound : settings.endSound;
                log(kind + " — " + event.lesson().name() + " о " + event.scheduledAt().toLocalTime().format(Lesson.TIME_FORMAT));
            }
            playSound(sound, kind + " — " + event.name());
        });
    }

    private Path resolveBellSound(ScheduledBell bell) {
        return switch (bell.audioSource()) {
            case START -> settings.startSound;
            case END -> settings.endSound;
            case CUSTOM -> bell.customSound();
        };
    }

    private void handleSkippedBell(SchedulerService.BellEvent event) {
        SwingUtilities.invokeLater(() -> log("ПРОПУЩЕНО — " + event.name() + " о "
                + event.scheduledAt().toLocalTime().format(Lesson.TIME_FORMAT)));
    }

    private void updateNextBell(SchedulerService.NextBell next) {
        SwingUtilities.invokeLater(() -> {
            lastNextBell = next;
            skipNextButton.setEnabled(next != null && settings.scheduleEnabled);
            if (!settings.scheduleEnabled) {
                nextTimeLabel.setText("—");
                nextDetailsLabel.setText("Розклад вимкнений");
                nextCountdownLabel.setText("Увімкни його в налаштуваннях");
                return;
            }
            if (next == null) {
                nextTimeLabel.setText("—");
                nextDetailsLabel.setText("Наступний дзвінок не запланований");
                nextCountdownLabel.setText("Додай урок або дзвінок у вкладці «Таблиця»");
                return;
            }

            long totalSeconds = Math.max(0, Duration.between(LocalDateTime.now(), next.at()).getSeconds());
            long days = totalSeconds / 86400;
            long hours = (totalSeconds % 86400) / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;
            StringBuilder countdown = new StringBuilder("Через ");
            if (days > 0) countdown.append(days).append(" дн ");
            if (hours > 0 || days > 0) countdown.append(hours).append(" год ");
            countdown.append(minutes).append(" хв ").append(seconds).append(" с");

            String type = switch (next.type()) {
                case START -> "Початок уроку";
                case END -> "Кінець уроку";
                case BELL -> "Окремий дзвінок";
            };
            nextTimeLabel.setText(next.at().toLocalTime().format(Lesson.TIME_FORMAT));
            nextDetailsLabel.setText(next.name() + " • " + type + " • " + next.at().format(NEXT_DATE));
            nextCountdownLabel.setText(countdown.toString());
        });
    }

    private void skipNextBell() {
        if (scheduler == null) return;
        SchedulerService.NextBell skipped = scheduler.skipNext();
        if (skipped == null) {
            DarkDialogs.message(this, "SchoolBell", "Немає запланованого дзвінка, який можна пропустити.");
            return;
        }
        String type = switch (skipped.type()) {
            case START -> "початок";
            case END -> "кінець";
            case BELL -> "окремий дзвінок";
        };
        log("Наступний дзвінок пропущено: " + skipped.name() + " — " + type + " о "
                + skipped.at().toLocalTime().format(Lesson.TIME_FORMAT));
    }

    private void chooseSound(boolean start) {
        Path current = start ? settings.startSound : settings.endSound;
        Path path = DarkDialogs.chooseAudio(this, start ? "Звук початку уроку" : "Звук кінця уроку", current);
        if (path == null) return;
        if (start) {
            settings.startSound = path;
            startSoundField.setText(path.toString());
        } else {
            settings.endSound = path;
            endSoundField.setText(path.toString());
        }
        saveQuietly();
        log("Вибрано аудіо: " + path.getFileName());
    }

    private void playSound(boolean start, String context) {
        playSound(start ? settings.startSound : settings.endSound, context);
    }

    private void playSound(Path sound, String context) {
        if (sound == null) {
            log(context + ": звук не вибрано.");
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        String osName = System.getProperty("os.name", "").toLowerCase();
        String backend = osName.contains("win")
                ? "Windows Media Foundation / MCI"
                : (osName.contains("linux")
                    ? "Linux ffplay / mpv / GStreamer / Java Sound"
                    : "Java SourceDataLine");
        try {
            log(context + ": запуск «" + sound.getFileName() + "», " + settings.volume + "%, backend: " + backend
                    + ", " + Files.size(sound) + " байт.");
        } catch (Exception ex) {
            log(context + ": запуск «" + sound.getFileName() + "», " + settings.volume + "%, backend: " + backend + ".");
        }
        audioPlayer.play(sound, settings.volume, error -> SwingUtilities.invokeLater(() -> {
            log("ПОМИЛКА: " + error);
            DarkDialogs.message(this, "Помилка аудіо", error);
        }));
    }

    private void addLesson() {
        Lesson lesson = new LessonDialog(this, null).showDialog();
        if (lesson != null) {
            settings.lessons.add(lesson);
            tableModel.refresh();
            saveQuietly();
            log("Додано урок: " + lesson.name() + " " + lesson.startText() + "–" + lesson.endText());
        }
    }

    private void addBell() {
        ScheduledBell bell = new BellDialog(this, null).showDialog();
        if (bell != null) {
            settings.bells.add(bell);
            tableModel.refresh();
            saveQuietly();
            log("Додано дзвінок: " + bell.name() + " " + bell.timeText() + " • " + bell.audioSource().display());
        }
    }

    private Object selectedScheduleEntry() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return null;
        int modelRow = table.convertRowIndexToModel(viewRow);
        return tableModel.entryAt(modelRow);
    }

    private void editSelected() {
        Object entry = selectedScheduleEntry();
        if (entry == null) {
            DarkDialogs.message(this, "SchoolBell", "Спочатку вибери запис у таблиці.");
            return;
        }
        if (entry instanceof Lesson lesson) {
            Lesson edited = new LessonDialog(this, lesson).showDialog();
            if (edited != null) log("Оновлено урок: " + edited.name() + " " + edited.startText() + "–" + edited.endText());
        } else if (entry instanceof ScheduledBell bell) {
            ScheduledBell edited = new BellDialog(this, bell).showDialog();
            if (edited != null) log("Оновлено дзвінок: " + edited.name() + " " + edited.timeText() + " • " + edited.audioSource().display());
        }
        tableModel.refresh();
        saveQuietly();
    }

    private void deleteSelected() {
        Object entry = selectedScheduleEntry();
        if (entry == null) return;
        String name = entry instanceof Lesson l ? l.name() : ((ScheduledBell) entry).name();
        String type = entry instanceof Lesson ? "урок" : "дзвінок";
        if (DarkDialogs.confirm(this, "Видалення", "Видалити " + type + " «" + name + "»?")) {
            if (entry instanceof Lesson lesson) settings.lessons.remove(lesson);
            else settings.bells.remove((ScheduledBell) entry);
            tableModel.refresh();
            saveQuietly();
            log("Видалено: " + name);
        }
    }

    private void duplicateSelected() {
        Object entry = selectedScheduleEntry();
        if (entry == null) {
            DarkDialogs.message(this, "SchoolBell", "Спочатку вибери запис у таблиці.");
            return;
        }
        if (entry instanceof Lesson lesson) {
            Lesson copy = new Lesson(lesson.name() + " копія", lesson.start(), lesson.end(), lesson.days(), lesson.enabled());
            settings.lessons.add(copy);
            log("Дубльовано урок: " + lesson.name());
        } else {
            ScheduledBell bell = (ScheduledBell) entry;
            ScheduledBell copy = new ScheduledBell(bell.name() + " копія", bell.time(), bell.days(), bell.enabled(),
                    bell.audioSource(), bell.customSound());
            settings.bells.add(copy);
            log("Дубльовано дзвінок: " + bell.name());
        }
        tableModel.refresh();
        saveQuietly();
    }

    private void sortSchedule() {
        tableModel.sortByTime();
        log("Таблицю відсортовано за часом.");
    }

    private void log(String message) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logArea.append("[" + time + "] " + message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void saveQuietly() {
        try {
            store.save(settings);
        } catch (Exception ex) {
            log("ПОМИЛКА збереження: " + ex.getMessage());
        }
    }

    private void updateScheduleStatus() {
        sidebarStatus.setText(settings.scheduleEnabled ? "● Розклад активний" : "● Розклад вимкнений");
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Відкрити SchoolBell");
            MenuItem ring = new MenuItem("Дзвонити зараз");
            MenuItem exit = new MenuItem("Вийти");
            open.addActionListener(e -> SwingUtilities.invokeLater(this::restoreFromTray));
            ring.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                log("РУЧНИЙ ДЗВІНОК із трею.");
                playSound(true, "Ручний дзвінок");
            }));
            exit.addActionListener(e -> SwingUtilities.invokeLater(this::exitApplication));
            menu.add(open);
            menu.add(ring);
            menu.addSeparator();
            menu.add(exit);
            trayIcon = new TrayIcon(createTrayImage(), "SchoolBell", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> SwingUtilities.invokeLater(this::restoreFromTray));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception ignored) {
            trayIcon = null;
        }
    }

    private void restoreFromTray() {
        setVisible(true);
        setExtendedState(JFrame.NORMAL);
        toFront();
        requestFocus();
    }

    private static Image createTrayImage() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.ACCENT);
        g.fillOval(3, 3, 26, 26);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawArc(9, 7, 14, 14, 20, 140);
        g.drawLine(9, 16, 7, 21);
        g.drawLine(23, 16, 25, 21);
        g.drawLine(7, 21, 25, 21);
        g.fillOval(14, 23, 4, 4);
        g.dispose();
        return image;
    }

    private void handleCloseRequest() {
        if (settings.minimizeToTray && trayIcon != null) {
            setVisible(false);
            trayIcon.displayMessage("SchoolBell", "Програма продовжує стежити за розкладом у фоні.", TrayIcon.MessageType.INFO);
        } else {
            exitApplication();
        }
    }

    private void exitApplication() {
        if (exiting) return;
        exiting = true;
        saveQuietly();
        if (scheduler != null) scheduler.close();
        if (neptunAlertService != null) neptunAlertService.close();
        if (alertsInUaAlertService != null) alertsInUaAlertService.close();
        audioPlayer.close();
        if (trayIcon != null) {
            try { SystemTray.getSystemTray().remove(trayIcon); } catch (Exception ignored) {}
        }
        dispose();
        System.exit(0);
    }

    private static String pathText(Path path) {
        return path == null ? "Не вибрано" : path.toString();
    }

    private void runSystemAction(java.util.concurrent.Callable<SystemIntegration.Result> task,
                                 java.util.function.Consumer<SystemIntegration.Result> done) {
        new SwingWorker<SystemIntegration.Result, Void>() {
            @Override protected SystemIntegration.Result doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                try {
                    done.accept(get());
                } catch (Exception ex) {
                    done.accept(new SystemIntegration.Result(false,
                            ex.getMessage() == null ? "Невідома помилка." : ex.getMessage()));
                }
            }
        }.execute();
    }

    private static final class ScheduleTableModel extends AbstractTableModel {
        private final List<Lesson> lessons;
        private final List<ScheduledBell> bells;
        private final Runnable changed;
        private final List<Object> rows = new ArrayList<>();
        private final String[] columns = {"Активний", "Тип", "Назва", "Початок / дзвінок", "Кінець", "Дні", "Аудіо"};

        private ScheduleTableModel(List<Lesson> lessons, List<ScheduledBell> bells, Runnable changed) {
            this.lessons = lessons;
            this.bells = bells;
            this.changed = changed;
            refresh();
        }

        void refresh() {
            rows.clear();
            rows.addAll(lessons);
            rows.addAll(bells);
            fireTableDataChanged();
        }

        void sortByTime() {
            rows.sort(Comparator.comparing(ScheduleTableModel::timeOf)
                    .thenComparing(ScheduleTableModel::nameOf, String.CASE_INSENSITIVE_ORDER));
            fireTableDataChanged();
        }

        Object entryAt(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }

        private static java.time.LocalTime timeOf(Object entry) {
            return entry instanceof Lesson l ? l.start() : ((ScheduledBell) entry).time();
        }

        private static String nameOf(Object entry) {
            return entry instanceof Lesson l ? l.name() : ((ScheduledBell) entry).name();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int columnIndex) { return columnIndex == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return columnIndex == 0; }

        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            Object entry = rows.get(rowIndex);
            if (entry instanceof Lesson l) {
                return switch (columnIndex) {
                    case 0 -> l.enabled();
                    case 1 -> "Урок";
                    case 2 -> l.name();
                    case 3 -> l.startText();
                    case 4 -> l.endText();
                    case 5 -> l.daysDisplay();
                    case 6 -> "Початок / кінець";
                    default -> "";
                };
            }
            ScheduledBell b = (ScheduledBell) entry;
            return switch (columnIndex) {
                case 0 -> b.enabled();
                case 1 -> "Дзвінок";
                case 2 -> b.name();
                case 3 -> b.timeText();
                case 4 -> "—";
                case 5 -> b.daysDisplay();
                case 6 -> b.audioSource().display();
                default -> "";
            };
        }

        @Override public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != 0 || !(aValue instanceof Boolean enabled)) return;
            Object entry = rows.get(rowIndex);
            if (entry instanceof Lesson lesson) lesson.setEnabled(enabled);
            else ((ScheduledBell) entry).setEnabled(enabled);
            fireTableCellUpdated(rowIndex, columnIndex);
            changed.run();
        }
    }

}
