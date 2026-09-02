package com.mystersay.schoolbell;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.LineBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.*;

public final class Theme {
    public static final Color BG = new Color(15, 17, 21);
    public static final Color SIDEBAR = new Color(18, 20, 25);
    public static final Color CARD = new Color(24, 27, 33);
    public static final Color CARD_2 = new Color(29, 33, 40);
    public static final Color FIELD = new Color(34, 38, 46);
    public static final Color HOVER = new Color(41, 46, 55);
    public static final Color ACCENT = new Color(99, 126, 255);
    public static final Color ACCENT_2 = new Color(77, 104, 235);
    public static final Color BORDER = new Color(48, 54, 64);
    public static final Color WHITE = Color.WHITE;
    public static final Color DANGER = new Color(183, 67, 78);
    public static final Color SUCCESS = new Color(71, 177, 119);

    private Theme() {}

    public static void install() {
        try {
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception ignored) {}

        Font font = preferredFont(14f, Font.PLAIN);
        Font bold = preferredFont(14f, Font.BOLD);
        Border fieldBorder = BorderFactory.createCompoundBorder(
                new LineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(7, 9, 7, 9));

        Object[][] values = {
                {"control", BG}, {"info", CARD}, {"nimbusBase", ACCENT},
                {"nimbusBlueGrey", CARD_2}, {"nimbusLightBackground", FIELD},
                {"text", WHITE}, {"window", BG}, {"menu", CARD},
                {"windowText", WHITE}, {"textText", WHITE}, {"controlText", WHITE},
                {"menuText", WHITE}, {"infoText", WHITE}, {"inactiveCaptionText", WHITE},
                {"Label.foreground", WHITE}, {"Label.disabledForeground", WHITE},
                {"Panel.background", BG},
                {"Button.background", FIELD}, {"Button.foreground", WHITE}, {"Button.select", HOVER},
                {"Button.disabledText", WHITE}, {"Button.focus", ACCENT},
                {"ToggleButton.background", FIELD}, {"ToggleButton.foreground", WHITE},
                {"CheckBox.background", BG}, {"CheckBox.foreground", WHITE},
                {"CheckBox.disabledText", WHITE}, {"RadioButton.background", BG},
                {"RadioButton.foreground", WHITE}, {"RadioButton.disabledText", WHITE},
                {"TextField.background", FIELD}, {"TextField.foreground", WHITE},
                {"TextField.caretForeground", WHITE}, {"TextField.inactiveForeground", WHITE},
                {"TextField.border", fieldBorder},
                {"FormattedTextField.background", FIELD}, {"FormattedTextField.foreground", WHITE},
                {"FormattedTextField.caretForeground", WHITE}, {"FormattedTextField.border", fieldBorder},
                {"PasswordField.background", FIELD}, {"PasswordField.foreground", WHITE},
                {"PasswordField.caretForeground", WHITE}, {"PasswordField.border", fieldBorder},
                {"TextArea.background", FIELD}, {"TextArea.foreground", WHITE},
                {"TextArea.caretForeground", WHITE},
                {"TextPane.background", FIELD}, {"TextPane.foreground", WHITE},
                {"EditorPane.background", FIELD}, {"EditorPane.foreground", WHITE},
                {"ComboBox.background", FIELD}, {"ComboBox.foreground", WHITE},
                {"ComboBox.selectionBackground", HOVER}, {"ComboBox.selectionForeground", WHITE},
                {"List.background", FIELD}, {"List.foreground", WHITE},
                {"List.selectionBackground", HOVER}, {"List.selectionForeground", WHITE},
                {"Table.background", CARD}, {"Table.foreground", WHITE},
                {"Table.selectionBackground", new Color(53, 62, 88)}, {"Table.selectionForeground", WHITE},
                {"Table.gridColor", BORDER}, {"Table.focusCellForeground", WHITE},
                {"Table.focusCellBackground", new Color(53, 62, 88)},
                {"TableHeader.background", CARD_2}, {"TableHeader.foreground", WHITE},
                {"ScrollPane.background", BG}, {"ScrollPane.foreground", WHITE},
                {"Viewport.background", CARD},
                {"ScrollBar.background", CARD_2}, {"ScrollBar.thumb", new Color(70, 76, 88)},
                {"ScrollBar.thumbHighlight", new Color(83, 90, 104)},
                {"ScrollBar.thumbDarkShadow", new Color(55, 60, 70)},
                {"ScrollBar.track", CARD_2},
                {"Slider.background", BG}, {"Slider.foreground", ACCENT},
                {"Slider.highlight", ACCENT}, {"Slider.focus", ACCENT},
                {"Separator.background", BORDER}, {"Separator.foreground", BORDER},
                {"MenuBar.background", CARD}, {"MenuBar.foreground", WHITE},
                {"Menu.background", CARD}, {"Menu.foreground", WHITE},
                {"Menu.selectionBackground", HOVER}, {"Menu.selectionForeground", WHITE},
                {"MenuItem.background", CARD}, {"MenuItem.foreground", WHITE},
                {"MenuItem.selectionBackground", HOVER}, {"MenuItem.selectionForeground", WHITE},
                {"PopupMenu.background", CARD}, {"PopupMenu.foreground", WHITE},
                {"OptionPane.background", BG}, {"OptionPane.foreground", WHITE},
                {"OptionPane.messageForeground", WHITE},
                {"FileChooser.background", BG}, {"FileChooser.foreground", WHITE},
                {"ToolTip.background", FIELD}, {"ToolTip.foreground", WHITE},
                {"Spinner.background", FIELD}, {"Spinner.foreground", WHITE},
                {"TabbedPane.background", BG}, {"TabbedPane.foreground", WHITE},
                {"TabbedPane.selected", CARD}, {"TabbedPane.contentAreaColor", BG},
                {"ProgressBar.background", FIELD}, {"ProgressBar.foreground", ACCENT},
                {"ProgressBar.selectionForeground", WHITE}, {"ProgressBar.selectionBackground", WHITE},
                {"Tree.background", FIELD}, {"Tree.foreground", WHITE},
                {"Tree.textForeground", WHITE}, {"Tree.selectionForeground", WHITE},
                {"Tree.selectionBackground", HOVER},
                {"InternalFrame.activeTitleBackground", CARD_2},
                {"InternalFrame.activeTitleForeground", WHITE},
                {"InternalFrame.inactiveTitleBackground", CARD_2},
                {"InternalFrame.inactiveTitleForeground", WHITE},
                {"TitledBorder.titleColor", WHITE},
                {"defaultFont", font}, {"Label.font", font}, {"Button.font", bold},
                {"CheckBox.font", font}, {"RadioButton.font", font}, {"TextField.font", font},
                {"TextArea.font", font}, {"Table.font", font}, {"TableHeader.font", bold},
                {"Menu.font", font}, {"MenuItem.font", font}, {"ToolTip.font", font}
        };

        for (Object[] entry : values) {
            Object value = entry[1];
            if (value instanceof Color color) value = new ColorUIResource(color);
            if (value instanceof Font f) value = new FontUIResource(f);
            UIManager.put(entry[0], value);
        }

        UIManager.put("CheckBox.icon", new DarkCheckBoxIcon());
    }

    public static Font preferredFont(float size, int style) {
        String[] candidates = {"Segoe UI", "Inter", "Noto Sans", "Arial", "SansSerif"};
        String[] installed = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String candidate : candidates) {
            for (String family : installed) {
                if (family.equalsIgnoreCase(candidate)) return new Font(family, style, Math.round(size));
            }
        }
        return new Font(Font.SANS_SERIF, style, Math.round(size));
    }

    public static void forceDark(Component component) {
        if (component instanceof JComponent jc) {
            jc.setForeground(WHITE);
            if (jc instanceof JTextField || jc instanceof JTextArea || jc instanceof JTextPane
                    || jc instanceof JEditorPane || jc instanceof JList<?> || jc instanceof JTable
                    || jc instanceof JComboBox<?> || jc instanceof JSpinner) {
                jc.setBackground(FIELD);
            } else if (jc instanceof JScrollPane) {
                jc.setBackground(BG);
            } else if (!(jc instanceof UIComponents.RoundBellButton)) {
                if (!(jc instanceof UIComponents.ModernButton) && !(jc instanceof UIComponents.NavButton)) {
                    jc.setBackground(BG);
                }
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) forceDark(child);
        }
    }

    private static final class DarkCheckBoxIcon implements Icon {
        private static final int SIZE = 16;

        @Override public int getIconWidth() { return SIZE; }
        @Override public int getIconHeight() { return SIZE; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            AbstractButton button = c instanceof AbstractButton b ? b : null;
            boolean selected = button != null && button.isSelected();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(selected ? ACCENT_2 : FIELD);
            g2.fillRoundRect(x, y, SIZE, SIZE, 5, 5);
            g2.setColor(selected ? ACCENT : BORDER);
            g2.drawRoundRect(x, y, SIZE - 1, SIZE - 1, 5, 5);
            if (selected) {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x + 4, y + 8, x + 7, y + 11);
                g2.drawLine(x + 7, y + 11, x + 12, y + 5);
            }
            g2.dispose();
        }
    }

}
