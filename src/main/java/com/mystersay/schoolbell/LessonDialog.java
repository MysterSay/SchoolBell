package com.mystersay.schoolbell;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

public final class LessonDialog extends JDialog {
    private final JTextField nameField = new JTextField(24);
    private final JTextField startField = new JTextField(8);
    private final JTextField endField = new JTextField(8);
    private final JCheckBox enabledBox = new JCheckBox("Активний", true);
    private final Map<DayOfWeek, JCheckBox> dayBoxes = new EnumMap<>(DayOfWeek.class);
    private Lesson result;
    private final Lesson editing;

    public LessonDialog(Window owner, Lesson lesson) {
        super(owner, lesson == null ? "Додати урок" : "Редагувати урок", ModalityType.APPLICATION_MODAL);
        setUndecorated(true);
        this.editing = lesson;
        buildUi();
        getRootPane().setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1));
        loadLesson(lesson);
        pack();
        setMinimumSize(new Dimension(560, getHeight()));
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    public Lesson showDialog() {
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
        JLabel title = new JLabel(editing == null ? "Новий урок" : "Редагування уроку");
        title.setForeground(Color.WHITE);
        title.setFont(Theme.preferredFont(23f, Font.BOLD));
        JLabel hint = new JLabel("Вкажи час і дні, коли цей урок має працювати.");
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

        addRow(card, gc, 0, "Назва уроку", nameField);
        addRow(card, gc, 1, "Початок (HH:mm)", startField);
        addRow(card, gc, 2, "Кінець (HH:mm)", endField);

        enabledBox.setOpaque(false);
        enabledBox.setForeground(Color.WHITE);
        gc.gridx = 0;
        gc.gridy = 3;
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

        gc.gridy = 4;
        JLabel daysTitle = new JLabel("Дні тижня");
        daysTitle.setForeground(Color.WHITE);
        card.add(daysTitle, gc);
        gc.gridy = 5;
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

        cancel.addActionListener(e -> dispose());
        save.addActionListener(e -> saveLesson());
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

    private void loadLesson(Lesson lesson) {
        EnumSet<DayOfWeek> days;
        if (lesson == null) {
            nameField.setText("Урок");
            startField.setText("08:00");
            endField.setText("08:45");
            enabledBox.setSelected(true);
            days = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        } else {
            nameField.setText(lesson.name());
            startField.setText(lesson.startText());
            endField.setText(lesson.endText());
            enabledBox.setSelected(lesson.enabled());
            days = lesson.days();
        }
        for (Map.Entry<DayOfWeek, JCheckBox> e : dayBoxes.entrySet()) {
            e.getValue().setSelected(days.contains(e.getKey()));
        }
        SwingUtilities.invokeLater(() -> nameField.selectAll());
    }

    private void saveLesson() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            showError("Вкажи назву уроку.");
            return;
        }

        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(startField.getText().trim(), Lesson.TIME_FORMAT);
            end = LocalTime.parse(endField.getText().trim(), Lesson.TIME_FORMAT);
        } catch (Exception ex) {
            showError("Час потрібно вводити у форматі HH:mm, наприклад 08:30.");
            return;
        }

        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        dayBoxes.forEach((day, box) -> {
            if (box.isSelected()) days.add(day);
        });
        if (days.isEmpty()) {
            showError("Вибери хоча б один день тижня.");
            return;
        }

        if (editing == null) {
            result = new Lesson(name, start, end, days, enabledBox.isSelected());
        } else {
            editing.setName(name);
            editing.setStart(start);
            editing.setEnd(end);
            editing.setDays(days);
            editing.setEnabled(enabledBox.isSelected());
            result = editing;
        }
        dispose();
    }

    private void showError(String text) {
        DarkDialogs.message(this, "Помилка", text);
    }
}
