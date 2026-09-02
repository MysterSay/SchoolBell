package com.mystersay.schoolbell;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

public final class BellDialog extends JDialog {
    private final JTextField nameField = new JTextField(24);
    private final JTextField timeField = new JTextField(8);
    private final JCheckBox enabledBox = new JCheckBox("Активний", true);
    private final JComboBox<String> audioBox = new JComboBox<>(new String[]{"Початок", "Кінець", "Власне"});
    private final JTextField customSoundField = new JTextField();
    private final UIComponents.ModernButton chooseCustomButton = new UIComponents.ModernButton("Обрати...");
    private final JPanel customAudioRow = new JPanel(new BorderLayout(8, 0));
    private final Map<DayOfWeek, JCheckBox> dayBoxes = new EnumMap<>(DayOfWeek.class);
    private final ScheduledBell editing;
    private ScheduledBell result;
    private Path customSound;

    public BellDialog(Window owner, ScheduledBell bell) {
        super(owner, bell == null ? "Додати дзвінок" : "Редагувати дзвінок", ModalityType.APPLICATION_MODAL);
        setUndecorated(true);
        this.editing = bell;
        buildUi();
        getRootPane().setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));
        loadBell(bell);
        pack();
        setMinimumSize(new Dimension(610, getHeight()));
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    public ScheduledBell showDialog() {
        setVisible(true);
        return result;
    }

    private void buildUi() {
        getContentPane().setBackground(Theme.BG);
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(Theme.BG);
        root.setBorder(new EmptyBorder(22, 22, 22, 22));
        setContentPane(root);

        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 4));
        heading.setOpaque(false);
        JLabel title = new JLabel(editing == null ? "Новий дзвінок" : "Редагування дзвінка");
        title.setForeground(Color.WHITE);
        title.setFont(Theme.preferredFont(23f, Font.BOLD));
        JLabel hint = new JLabel("Вкажи час, дні та звук для окремого дзвінка.");
        hint.setForeground(Color.WHITE);
        hint.setFont(Theme.preferredFont(13f, Font.PLAIN));
        heading.add(title);
        heading.add(hint);
        root.add(heading, BorderLayout.NORTH);

        UIComponents.RoundedPanel card = new UIComponents.RoundedPanel(new GridBagLayout());
        card.setBorder(new EmptyBorder(18, 18, 18, 18));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(7, 7, 7, 7);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        addRow(card, gc, 0, "Назва", nameField);
        addRow(card, gc, 1, "Дзвінок (HH:mm)", timeField);
        addRow(card, gc, 2, "Аудіо", audioBox);

        customSoundField.setEditable(false);
        customSoundField.setForeground(Color.WHITE);
        customSoundField.setBackground(Theme.FIELD);
        customAudioRow.setOpaque(false);
        customAudioRow.add(customSoundField, BorderLayout.CENTER);
        customAudioRow.add(chooseCustomButton, BorderLayout.EAST);
        addRow(card, gc, 3, "Власний файл", customAudioRow);

        enabledBox.setOpaque(false);
        enabledBox.setForeground(Color.WHITE);
        gc.gridx = 0;
        gc.gridy = 4;
        gc.gridwidth = 2;
        gc.weightx = 1;
        card.add(enabledBox, gc);

        JPanel daysPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        daysPanel.setOpaque(false);
        String[] names = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"};
        for (DayOfWeek day : DayOfWeek.values()) {
            JCheckBox box = new JCheckBox(names[day.getValue() - 1]);
            box.setOpaque(false);
            box.setForeground(Color.WHITE);
            dayBoxes.put(day, box);
            daysPanel.add(box);
        }

        gc.gridy = 5;
        JLabel daysTitle = new JLabel("Дні тижня");
        daysTitle.setForeground(Color.WHITE);
        card.add(daysTitle, gc);
        gc.gridy = 6;
        card.add(daysPanel, gc);

        root.add(card, BorderLayout.CENTER);

        UIComponents.ModernButton cancel = new UIComponents.ModernButton("Скасувати");
        UIComponents.ModernButton save = new UIComponents.ModernButton(
                "Зберегти", Theme.ACCENT_2, Theme.ACCENT, Theme.ACCENT_2, 14);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(save);
        root.add(buttons, BorderLayout.SOUTH);

        audioBox.addActionListener(e -> updateCustomAudioState());
        chooseCustomButton.addActionListener(e -> chooseCustomAudio());
        cancel.addActionListener(e -> dispose());
        save.addActionListener(e -> saveBell());
        getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private static void addRow(JPanel panel, GridBagConstraints gc, int row, String label, JComponent component) {
        gc.gridy = row;
        gc.gridx = 0;
        gc.gridwidth = 1;
        gc.weightx = 0;
        JLabel title = new JLabel(label);
        title.setForeground(Color.WHITE);
        panel.add(title, gc);
        gc.gridx = 1;
        gc.weightx = 1;
        component.setForeground(Color.WHITE);
        if (component instanceof JTextField) component.setBackground(Theme.FIELD);
        panel.add(component, gc);
    }

    private void loadBell(ScheduledBell bell) {
        EnumSet<DayOfWeek> days;
        if (bell == null) {
            nameField.setText("Дзвінок");
            timeField.setText("08:00");
            enabledBox.setSelected(true);
            audioBox.setSelectedIndex(0);
            customSound = null;
            days = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        } else {
            nameField.setText(bell.name());
            timeField.setText(bell.timeText());
            enabledBox.setSelected(bell.enabled());
            audioBox.setSelectedIndex(switch (bell.audioSource()) {
                case START -> 0;
                case END -> 1;
                case CUSTOM -> 2;
            });
            customSound = bell.customSound();
            days = bell.days();
        }
        customSoundField.setText(customSound == null ? "Не вибрано" : customSound.toString());
        for (Map.Entry<DayOfWeek, JCheckBox> e : dayBoxes.entrySet()) {
            e.getValue().setSelected(days.contains(e.getKey()));
        }
        updateCustomAudioState();
        SwingUtilities.invokeLater(() -> nameField.selectAll());
    }

    private void updateCustomAudioState() {
        boolean custom = audioBox.getSelectedIndex() == 2;
        customSoundField.setEnabled(custom);
        chooseCustomButton.setEnabled(custom);
        customAudioRow.setVisible(custom);
        pack();
    }

    private void chooseCustomAudio() {
        Path selected = DarkDialogs.chooseAudio(this, "Власний звук дзвінка", customSound);
        if (selected != null) {
            customSound = selected;
            customSoundField.setText(selected.toString());
        }
    }

    private void saveBell() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            showError("Вкажи назву дзвінка.");
            return;
        }

        LocalTime time;
        try {
            time = LocalTime.parse(timeField.getText().trim(), Lesson.TIME_FORMAT);
        } catch (Exception ex) {
            showError("Час потрібно вводити у форматі HH:mm, наприклад 08:30.");
            return;
        }

        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        dayBoxes.forEach((day, box) -> { if (box.isSelected()) days.add(day); });
        if (days.isEmpty()) {
            showError("Вибери хоча б один день тижня.");
            return;
        }

        ScheduledBell.AudioSource source = switch (audioBox.getSelectedIndex()) {
            case 1 -> ScheduledBell.AudioSource.END;
            case 2 -> ScheduledBell.AudioSource.CUSTOM;
            default -> ScheduledBell.AudioSource.START;
        };
        if (source == ScheduledBell.AudioSource.CUSTOM && customSound == null) {
            showError("Для пункту «Власне» вибери аудіофайл.");
            return;
        }

        if (editing == null) {
            result = new ScheduledBell(name, time, days, enabledBox.isSelected(), source, customSound);
        } else {
            editing.setName(name);
            editing.setTime(time);
            editing.setDays(days);
            editing.setEnabled(enabledBox.isSelected());
            editing.setAudioSource(source);
            editing.setCustomSound(customSound);
            result = editing;
        }
        dispose();
    }

    private void showError(String text) {
        DarkDialogs.message(this, "Помилка", text);
    }
}
